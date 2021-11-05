package scheduler.oplevel.struct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import profiler.MeasureTools;
import scheduler.Request;
import scheduler.context.LayeredTPGContext;
import scheduler.oplevel.context.OPGSTPGContext;
import scheduler.oplevel.context.OPLayeredContext;
import scheduler.oplevel.context.OPSchedulerContext;
import scheduler.oplevel.impl.tpg.OPBFSScheduler;
import utils.lib.ConcurrentHashMap;

import java.util.*;
import java.util.concurrent.CyclicBarrier;

import static common.CONTROL.enable_log;
import static scheduler.oplevel.impl.OPScheduler.getTaskId;

/**
 * TPG  -> Partition -> Key:OperationChain -> Operation-Operation-Operation...
 * |            |
 * |            -> Key: OperationChain -> Operation-Operation...
 * |
 * -> Partition ...
 */

/**
 * TPG  -> Key:OperationChain [ Operation-Operation-Operation...]
 * |
 * -> Key: OperationChain [ Operation-Operation... ]
 * |
 * -> Key: OperationChain [ Operation... ]
 */
public class TaskPrecedenceGraph<Context extends OPSchedulerContext> {
    private static final Logger log = LoggerFactory.getLogger(TaskPrecedenceGraph.class);

    public final int totalThreads;
    protected final int delta;//range of each partition. depends on the number of op in the stage.
    private final int NUM_ITEMS;
    CyclicBarrier barrier;
    public final Map<Integer, Context> threadToContextMap;
    private final ConcurrentHashMap<String, TableOCs> operationChains;//shared data structure.
    public final HashMap<Integer, Deque<OperationChain>> threadToOCs;

    public void reset(Context context) {
        operationChains.get("accounts").threadOCsMap.get(context.thisThreadId).holder_v1.clear();
        operationChains.get("bookEntries").threadOCsMap.get(context.thisThreadId).holder_v1.clear();
        threadToOCs.remove(context.thisThreadId);
        this.setOCs(context);
    }

    /**
     * @param totalThreads
     */
    public TaskPrecedenceGraph(int totalThreads, int delta, int NUM_ITEMS) {
        barrier = new CyclicBarrier(totalThreads);
        this.totalThreads = totalThreads;
        this.delta = delta;
        this.NUM_ITEMS = NUM_ITEMS;
        // all parameters in this class should be thread safe.
        operationChains = new ConcurrentHashMap<>();
        operationChains.put("accounts", new TableOCs(totalThreads));
        operationChains.put("bookEntries", new TableOCs(totalThreads));
        threadToContextMap = new HashMap<>();
        threadToOCs = new HashMap<>();
    }

    /**
     * Pre-create a bunch of OCs for each key in the table, which reduces the constant overhead during the runtime.
     * @param context
     */
    // TODO: if running multiple batches, this will be a problem.
    public void setOCs(Context context) {
        ArrayDeque<OperationChain> ocs = new ArrayDeque<>();
        int left_bound = context.thisThreadId * delta;
        int right_bound;
        if (context.thisThreadId == totalThreads - 1) {//last executor need to handle left-over
            right_bound = NUM_ITEMS;
        } else {
            right_bound = (context.thisThreadId + 1) * delta;
        }
        String _key;
        for (int key = left_bound; key < right_bound; key++) {
            _key = String.valueOf(key);
            OperationChain accOC = context.createTask("accounts", _key);
            OperationChain beOC = context.createTask("bookEntries", _key);
            operationChains.get("accounts").threadOCsMap.get(context.thisThreadId).holder_v1.put(_key, accOC);
            operationChains.get("bookEntries").threadOCsMap.get(context.thisThreadId).holder_v1.put(_key, beOC);
            ocs.add(accOC);
            ocs.add(beOC);
        }
        threadToOCs.put(context.thisThreadId, ocs);
    }

    public TableOCs getTableOCs(String table_name) {
        return operationChains.get(table_name);
    }

    public ConcurrentHashMap<String, TableOCs> getOperationChains() {
        return operationChains;
    }

    /**
     * set up functional dependencies among operations
     *
     * @param operation
     * @param request
     */
    public void setupOperationTDFD(Operation operation, Request request) {
        // TD
        OperationChain oc = addOperationToChain(operation);
        // FD
        if (request.condition_source != null)
            checkFD(oc, operation, request.table_name, request.src_key, request.condition_sourceTable, request.condition_source);
    }

    /**
     * During the first explore, we will group operations that can be executed sequentially following Temporal dependencies
     * And all groups constructs a group graph.
     * And then partition the group graph by cutting logical dependency edges.
     * And then sorting operations in each partition to execute sequentially on each thread, such that they can be batched for execution.
     *
     * @param context
     */
    public void firstTimeExploreTPG(Context context) {
        MeasureTools.BEGIN_FIRST_EXPLORE_TIME_MEASURE(context.thisThreadId);

        if (context instanceof OPLayeredContext) {
            ArrayDeque<Operation> roots = new ArrayDeque<>();
            for (OperationChain oc : threadToOCs.get(context.thisThreadId)) {
                if (!oc.getOperations().isEmpty()) {
                    oc.updateTDDependencies();
                    Operation head = oc.getOperations().first();
                    if (head.isRoot()) {
                        roots.add(head);
                    }
                    context.operations.addAll(oc.getOperations());
                    context.totalOsToSchedule += oc.getOperations().size();
                }
            }
            ((OPLayeredContext) context).buildBucketPerThread(context.operations, roots);
            if (enable_log) log.info("MaxLevel:" + (((OPLayeredContext) context).maxLevel));
        } else if (context instanceof OPGSTPGContext) {
            for (OperationChain oc : threadToOCs.get(context.thisThreadId)) {
                if (!oc.getOperations().isEmpty()) {
                    oc.updateTDDependencies();
                    Operation head = oc.getOperations().first();
                    context.totalOsToSchedule += oc.getOperations().size();
                    if (head.isRoot()) {
                        head.context.getListener().onRootStart(head);
                    }
                }
            }
        } else {
            throw new UnsupportedOperationException();
        }

        MeasureTools.END_FIRST_EXPLORE_TIME_MEASURE(context.thisThreadId);
    }

    public void secondTimeExploreTPG(Context context) {
        context.reset();
        MeasureTools.BEGIN_FIRST_EXPLORE_TIME_MEASURE(context.thisThreadId);
        if (context instanceof OPLayeredContext) {
            ArrayDeque<Operation> roots = new ArrayDeque<>();
            for (OperationChain oc : threadToOCs.get(context.thisThreadId)) {
                if (!oc.getOperations().isEmpty()) {
                    resetOp(oc);
                    Operation head = oc.getOperations().first();
                    if (head.isRoot()) {
                        roots.add(head);
                    }
                    context.operations.addAll(oc.getOperations());
                    context.totalOsToSchedule += oc.getOperations().size();
                }
            }
            ((OPLayeredContext) context).buildBucketPerThread(context.operations, roots);
            if (enable_log) log.info("MaxLevel:" + (((OPLayeredContext) context).maxLevel));
        } else if (context instanceof OPGSTPGContext) {
            for (OperationChain oc : threadToOCs.get(context.thisThreadId)) {
                if (!oc.getOperations().isEmpty()) {
                    resetOp(oc);
                    Operation head = oc.getOperations().first();
                    context.totalOsToSchedule += oc.getOperations().size();
                    if (head.isRoot()) {
                        head.context.getListener().onRootStart(head);
                    }
                }
            }
        } else {
            throw new UnsupportedOperationException();
        }

        MeasureTools.END_FIRST_EXPLORE_TIME_MEASURE(context.thisThreadId);
    }

    private void resetOp(OperationChain oc) {
        for (Operation op : oc.getOperations()) {
            op.resetDependencies();
            op.stateTransition(MetaTypes.OperationStateType.BLOCKED);
//            if (op.isFailed) { // transit state to aborted.
//                op.stateTransition(MetaTypes.OperationStateType.ABORTED);
//                for (Operation child : op.getHeader().getDescendants()) {
//                    child.stateTransition(MetaTypes.OperationStateType.ABORTED);
//                }
//            }
        }
    }

    /**
     * @param operation
     */
    private OperationChain addOperationToChain(Operation operation) {
        // DD: Get the Holder for the table, then get a map for each thread, then get the list of operations
        String table_name = operation.table_name;
        String primaryKey = operation.pKey;
        OperationChain retOc = getOC(table_name, primaryKey, operation.context.thisThreadId);
        retOc.addOperation(operation);
        return retOc;
    }

    private OperationChain getOC(String tableName, String pKey) {
        int threadId = Integer.parseInt(pKey) / delta;
        ConcurrentHashMap<String, OperationChain> holder = getTableOCs(tableName).threadOCsMap.get(threadId).holder_v1;
//        return holder.computeIfAbsent(pKey, s -> threadToContextMap.get(threadId).createTask(tableName, pKey));
        return holder.get(pKey);
    }

    private OperationChain getOC(String tableName, String pKey, int threadId) {
//        int threadId = Integer.parseInt(pKey) / delta;
        ConcurrentHashMap<String, OperationChain> holder = getTableOCs(tableName).threadOCsMap.get(threadId).holder_v1;
//        return holder.computeIfAbsent(pKey, s -> threadToContextMap.get(threadId).createTask(tableName, pKey));
        return holder.get(pKey);
    }

    private void checkFD(OperationChain curOC, Operation op, String table_name,
                         String key, String[] condition_sourceTable, String[] condition_source) {
        if (condition_source != null) {
            for (int index = 0; index < condition_source.length; index++) {
                if (table_name.equals(condition_sourceTable[index]) && key.equals(condition_source[index]))
                    continue;// no need to check data dependency on a key itself.
                OperationChain OCFromConditionSource = getOC(condition_sourceTable[index],
                        condition_source[index]);
                // dependency.getOperations().first().bid >= bid -- Check if checking only first ops bid is enough.
                if (OCFromConditionSource.getOperations().isEmpty() || OCFromConditionSource.getOperations().first().bid >= op.bid) {
                    OCFromConditionSource.addPotentialFDChildren(curOC, op);
                } else {
                    // All ops in transaction event involves writing to the states, therefore, we ignore edge case for read ops.
                    curOC.addFDParent(op, OCFromConditionSource); // record dependency
                }
            }
            curOC.checkPotentialFDChildrenOnNewArrival(op);
        }
    }
}