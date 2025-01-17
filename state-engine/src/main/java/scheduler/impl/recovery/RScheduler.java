package scheduler.impl.recovery;

import common.util.io.IOUtils;
import durability.logging.LoggingStrategy.ImplLoggingManager.PathLoggingManager;
import durability.logging.LoggingStrategy.ImplLoggingManager.WALManager;
import durability.logging.LoggingStrategy.LoggingManager;
import durability.struct.FaultToleranceRelax;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import profiler.MeasureTools;
import scheduler.Request;
import scheduler.context.recovery.RSContext;
import scheduler.impl.IScheduler;
import scheduler.struct.AbstractOperation;
import scheduler.struct.MetaTypes;
import scheduler.struct.recovery.Operation;
import scheduler.struct.recovery.OperationChain;
import scheduler.struct.recovery.TableOCs;
import scheduler.struct.recovery.TaskPrecedenceGraph;
import storage.SchemaRecord;
import storage.datatype.DataBox;
import storage.datatype.DoubleDataBox;
import storage.datatype.IntDataBox;
import transaction.function.AVG;
import transaction.function.DEC;
import transaction.function.INC;
import transaction.function.SUM;
import utils.AppConfig;
import utils.SOURCE_CONTROL;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static common.constants.TPConstants.Constant.MAX_INT;
import static common.constants.TPConstants.Constant.MAX_SPEED;
import static content.common.CommonMetaTypes.AccessType.*;
import static utils.FaultToleranceConstants.*;

public class RScheduler<Context extends RSContext> implements IScheduler<Context> {
    private static final Logger LOG = LoggerFactory.getLogger(RScheduler.class);
    public final int delta;
    public final TaskPrecedenceGraph<Context> tpg;
    public AtomicBoolean abortHandling = new AtomicBoolean(false);
    public int isLogging;
    public LoggingManager loggingManager;

    public RScheduler(int totalThreads, int NUM_ITEMS, int app) {
        this.delta = (int) Math.ceil(NUM_ITEMS / (double) totalThreads);
        this.tpg = new TaskPrecedenceGraph<>(totalThreads, delta, NUM_ITEMS, app);
    }
    @Override
    public void initTPG(int offset) {
        tpg.initTPG(offset);
    }
    @Override
    public void setLoggingManager(LoggingManager loggingManager) {
        this.loggingManager = loggingManager;
        if (loggingManager instanceof WALManager) {
            isLogging = LOGOption_wal;
        } else if (loggingManager instanceof PathLoggingManager) {
            isLogging = LOGOption_path;
            this.tpg.threadToPathRecord = ((PathLoggingManager) loggingManager).threadToPathRecord;
        } else {
            isLogging = LOGOption_no;
        }
        this.tpg.isLogging = this.isLogging;
    }
    @Override
    public boolean SubmitRequest(Context context, Request request) {
        context.push(request);
        return false;
    }

    @Override
    public void TxnSubmitBegin(Context context) {
        if (context.needCheckId) {
            context.needCheckId = false;
            context.groupId = this.loggingManager.getHistoryViews().checkGroupId(context.groupId);
        }
        context.requests.clear();
    }

    @Override
    public void TxnSubmitFinished(Context context) {
        MeasureTools.BEGIN_TPG_CONSTRUCTION_TIME_MEASURE(context.thisThreadId);
        int txnOpId = 0;
        for (Request request : context.requests) {
            long bid = request.txn_context.getBID();
            Operation set_op;
            switch (request.accessType) {
                case WRITE_ONLY:
                    set_op = new Operation(request.src_key, getTargetContext(request.src_key), request.table_name, request.txn_context, bid, request.accessType,
                            request.d_record, null, null, null, null);
                    set_op.value = request.value;
                    break;
                case READ_WRITE: // they can use the same method for processing
                case READ_WRITE_COND:
                    set_op = new Operation(request.src_key, getTargetContext(request.src_key), request.table_name, request.txn_context, bid, request.accessType,
                            request.d_record, request.function, request.condition, request.condition_records, request.success);
                    break;
                case READ_WRITE_COND_READ:
                case READ_WRITE_COND_READN:
                    set_op = new Operation(request.src_key, getTargetContext(request.src_key), request.table_name, request.txn_context, bid, request.accessType,
                            request.d_record, request.record_ref, request.function, request.condition, request.condition_records, request.success);
                    break;
                case READ_WRITE_READ:
                    set_op = new Operation(request.src_key, getTargetContext(request.src_key), request.table_name, request.txn_context, bid, request.accessType,
                            request.d_record, request.record_ref, request.function, null, null, request.success);
                    break;
                default:
                    throw new UnsupportedOperationException();
            }
            OperationChain curOC = tpg.addOperationToChain(set_op);
            set_op.setTxnOpId(txnOpId ++);
            if (request.condition_source != null) {
                MeasureTools.BEGIN_RECOVERY_HISTORY_INSPECT_MEASURE(context.thisThreadId);
                inspectDependency(context.groupId, curOC, set_op, request.table_name, request.src_key, request.condition_sourceTable, request.condition_source);
                MeasureTools.END_RECOVERY_HISTORY_INSPECT_MEASURE(context.thisThreadId);
            }
            MeasureTools.END_TPG_CONSTRUCTION_TIME_MEASURE(context.thisThreadId);
        }
    }

    @Override
    public void AddContext(int threadId, Context context) {
        tpg.threadToContextMap.put(threadId, context);
        tpg.setOCs(context);
    }

    @Override
    public void INITIALIZE(Context context) {
        if (FaultToleranceRelax.isTaskPlacing) {
            MeasureTools.BEGIN_RECOVERY_TASK_PLACING_MEASURE(context.thisThreadId);
            HashMap<String, List<Integer>> plan;
            if (!this.loggingManager.getHistoryViews().canInspectTaskPlacing(context.groupId)) {
                this.graphConstruct(context);
            }
            plan = this.loggingManager.inspectTaskPlacing(context.groupId, context.thisThreadId);
            for (Map.Entry<String, List<Integer>> entry : plan.entrySet()) {
                String table = entry.getKey();
                List<Integer> value = entry.getValue();
                for (int key : value) {
                    int taskId = getTaskId(String.valueOf(key), delta);
                    OperationChain oc = tpg.getOperationChains().get(table).threadOCsMap.get(taskId).holder_v1.get(String.valueOf(key));
                    context.allocatedTasks.add(oc);
                    context.totalTasks = context.totalTasks + oc.operations.size();
                }
            }
            MeasureTools.END_RECOVERY_TASK_PLACING_MEASURE(context.thisThreadId);
        } else {
            for (OperationChain oc : tpg.threadToOCs.get(context.thisThreadId)) {
                context.allocatedTasks.add(oc);
                context.totalTasks = context.totalTasks + oc.operations.size();
            }
        }
    }
    @Override
    public void PROCESS(Context context, long mark_ID) {
        OperationChain oc = context.ready_oc;
        for (Operation op : oc.operations) {
            if (op.pKey.equals(oc.getPrimaryKey())) {
                if (op.operationState.equals(MetaTypes.OperationStateType.EXECUTED) || op.operationState.equals(MetaTypes.OperationStateType.ABORTED)) {
                    continue;
                }
                if (op.pdCount.get() == 0) {
                    MeasureTools.BEGIN_SCHEDULE_USEFUL_TIME_MEASURE(context.thisThreadId);
                    execute(op, mark_ID, false);
                    if (op.isFailed) {
                        op.operationState = MetaTypes.OperationStateType.ABORTED;
                        checkAbort();
                    } else {
                        op.operationState = MetaTypes.OperationStateType.EXECUTED;
                    }
                    oc.level = oc.level + 1;
                    MeasureTools.END_SCHEDULE_USEFUL_TIME_MEASURE(context.thisThreadId);
                } else {
                    context.wait_op = op;
                    break;
                }
            } else {
                if (op.operationState.equals(MetaTypes.OperationStateType.BLOCKED)) {
                    op.pdCount.decrementAndGet();
                    op.operationState = MetaTypes.OperationStateType.READY;
                    oc.level = oc.level + 1;
                }
            }
        }
    }

    @Override
    public void EXPLORE(Context context) {
        context.next();
    }

    @Override
    public boolean FINISHED(Context context) {
        return context.isFinished;
    }

    @Override
    public void RESET(Context context) {
        context.needCheckId = true;
        context.isFinished = false;
        for (OperationChain oc : this.tpg.threadToOCs.get(context.thisThreadId)) {
            oc.level = 0;
            oc.operations.clear();
        }
        this.abortHandling.compareAndSet(true,false);
        MeasureTools.BEGIN_SCHEDULE_WAIT_TIME_MEASURE(context.thisThreadId);
        SOURCE_CONTROL.getInstance().waitForOtherThreads(context.thisThreadId);
        MeasureTools.END_SCHEDULE_WAIT_TIME_MEASURE(context.thisThreadId);
    }
    private void checkAbort() {
        this.abortHandling.compareAndSet(false, true);
    }
    private void REINITIALIZE(Context context) {
        context.isFinished = false;
        for (OperationChain oc : this.tpg.threadToOCs.get(context.thisThreadId)) {
            oc.level = 0;
            resetOp(oc);
        }
        context.scheduledTasks = 0;
        context.totalTasks = 0;
        INITIALIZE(context);
    }
    private void resetOp(OperationChain oc) {
        for (Operation op : oc.operations) {
            if (op.operationState.equals(MetaTypes.OperationStateType.ABORTED)) {
                oc.level = oc.level + 1;
            } else {
                op.operationState = MetaTypes.OperationStateType.BLOCKED;
            }
        }
    }

    @Override
    public void start_evaluation(Context context, long mark_ID, int num_events) {
        INITIALIZE(context);
        do {
            EXPLORE(context);
            PROCESS(context, mark_ID);
        } while (!FINISHED(context));
        SOURCE_CONTROL.getInstance().waitForOtherThreads(context.thisThreadId);
        if (this.abortHandling.get()) {
            LOG.info("Abort handling " + context.thisThreadId);
            REINITIALIZE(context);
            do {
                EXPLORE(context);
                PROCESS(context, mark_ID);
            } while (!FINISHED(context));
        }
        RESET(context);
    }
    public Context getTargetContext(String key) {
        int threadId =  Integer.parseInt(key) / delta;
        return tpg.threadToContextMap.get(threadId);
    }
    private void inspectDependency(long groupId, OperationChain curOC, Operation op, String table_name,
                                   String key, String[] condition_sourceTable, String[] condition_source){
        if (tpg.getApp() == 0) {
            inspectGSDependency(groupId, curOC, op, table_name, key, condition_sourceTable, condition_source);
        } else if (tpg.getApp() == 1) {
            inspectSLDependency(groupId, curOC, op, table_name, key, condition_sourceTable, condition_source);
        }
    }
    private void graphConstruct(Context context) {
        for (OperationChain oc : tpg.threadToOCs.get(context.thisThreadId)) {
            this.tpg.threadToPathRecord.get(context.thisThreadId).addNode(oc.getTableName(), oc.getPrimaryKey(),oc.operations.size());
           // IOUtils.println("Thread " + context.thisThreadId + " has " + oc.operations.size() + " operations in " + oc.getTableName() + " " + oc.getPrimaryKey());
        }
    }
    public void execute(Operation operation, long mark_ID, boolean clean) {
        if (operation.accessType.equals(READ_WRITE_COND_READ)) {
            Transfer_Fun(operation, mark_ID, clean);
            if (operation.record_ref != null) {
                operation.record_ref.setRecord(operation.d_record.content_.readPreValues(operation.bid));//read the resulting tuple.
            }
        } else if (operation.accessType.equals(READ_WRITE_COND)) {
            if (this.tpg.getApp() == 1) {//SL
                Transfer_Fun(operation, mark_ID, clean);
            }
        } else if (operation.accessType.equals(READ_WRITE)) {
            if (this.tpg.getApp() == 1) {
                Depo_Fun(operation, mark_ID, clean);
            }
        } else if (operation.accessType.equals(READ_WRITE_COND_READN)) {
            GrepSum_Fun(operation, mark_ID, clean);
            if (operation.record_ref != null) {
                operation.record_ref.setRecord(operation.d_record.content_.readPreValues(operation.bid));//read the resulting tuple.
            }
        } else if (operation.accessType.equals(READ_WRITE_READ)) {
            TollProcess_Fun(operation, mark_ID, clean);
        }
    }
    protected void Transfer_Fun(AbstractOperation operation, long previous_mark_ID, boolean clean) {
        Operation op = (Operation) operation;
        final long sourceAccountBalance;
        if (op.historyView == null) {
            SchemaRecord preValues = operation.condition_records[0].content_.readPreValues(operation.bid);
            sourceAccountBalance = preValues.getValues().get(1).getLong();
        } else {
            sourceAccountBalance = Long.parseLong(String.valueOf(op.historyView));
        }
        // apply function
        AppConfig.randomDelay();

        if (sourceAccountBalance > operation.condition.arg1
                && sourceAccountBalance > operation.condition.arg2) {
            // read
            SchemaRecord srcRecord = operation.s_record.content_.readPreValues(operation.bid);
            SchemaRecord tempo_record = new SchemaRecord(srcRecord);//tempo record

            if (operation.function instanceof INC) {
                tempo_record.getValues().get(1).incLong(sourceAccountBalance, operation.function.delta_long);//compute.
            } else if (operation.function instanceof DEC) {
                tempo_record.getValues().get(1).decLong(sourceAccountBalance, operation.function.delta_long);//compute.
            } else
                throw new UnsupportedOperationException();
            operation.d_record.content_.updateMultiValues(operation.bid, previous_mark_ID, clean, tempo_record);//it may reduce NUMA-traffic.
            synchronized (operation.success) {
                operation.success[0]++;
            }
        } else {
            op.isFailed = true;
        }
    }
    protected void Depo_Fun(AbstractOperation operation, long mark_ID, boolean clean) {
        SchemaRecord srcRecord = operation.s_record.content_.readPreValues(operation.bid);
        List<DataBox> values = srcRecord.getValues();
        //apply function to modify..
        AppConfig.randomDelay();
        SchemaRecord tempo_record;
        tempo_record = new SchemaRecord(values);//tempo record
        tempo_record.getValues().get(1).incLong(operation.function.delta_long);//compute.
        operation.s_record.content_.updateMultiValues(operation.bid, mark_ID, clean, tempo_record);//it may reduce NUMA-traffic.
    }
    protected void GrepSum_Fun(Operation operation, long previous_mark_ID, boolean clean) {
        int keysLength = operation.condition_records.length;
        SchemaRecord[] preValues = new SchemaRecord[operation.condition_records.length];
        long sum = 0;
        AppConfig.randomDelay();
        if (operation.historyView != null) {
            sum = Long.parseLong(String.valueOf(operation.historyView));
        } else {
            for (int i = 0; i < keysLength; i++) {
                preValues[i] = operation.condition_records[i].content_.readPreValues(operation.bid);
                sum += preValues[i].getValues().get(1).getLong();
            }
        }
        sum /= keysLength;
        SchemaRecord srcRecord = operation.s_record.content_.readPreValues(operation.bid);
        SchemaRecord tempo_record = new SchemaRecord(srcRecord);//tempo record
        if (operation.function.delta_long != -1) {
            if (operation.function instanceof SUM) {
                tempo_record.getValues().get(1).setLong(sum);//compute.
            } else
                throw new UnsupportedOperationException();
            operation.d_record.content_.updateMultiValues(operation.bid, previous_mark_ID, clean, tempo_record);//it may reduce NUMA-traffic.
        } else {
            operation.isFailed = true;
        }

    }
    protected void TollProcess_Fun(Operation operation, long previous_mark_ID, boolean clean) {
        AppConfig.randomDelay();
        List<DataBox> srcRecord = operation.s_record.record_.getValues();
        if (operation.function instanceof AVG) {
            if (operation.function.delta_double < MAX_SPEED) {
                double latestAvgSpeeds = srcRecord.get(1).getDouble();
                double lav;
                if (latestAvgSpeeds == 0) {//not initialized
                    lav = operation.function.delta_double;
                } else
                    lav = (latestAvgSpeeds + operation.function.delta_double) / 2;

                srcRecord.get(1).setDouble(lav);//write to state.
                operation.record_ref.setRecord(new SchemaRecord(new DoubleDataBox(lav)));//return updated record.
            } else {
                operation.isFailed = true;
            }
        } else {
            if (operation.function.delta_int < MAX_INT) {
                HashSet cnt_segment = srcRecord.get(1).getHashSet();
                cnt_segment.add(operation.function.delta_int);//update hashset; updated state also. TODO: be careful of this.
                operation.record_ref.setRecord(new SchemaRecord(new IntDataBox(cnt_segment.size())));//return updated record.
            } else {
                operation.isFailed = true;
            }
        }
    }
    private void inspectSLDependency(long groupId, OperationChain curOC, Operation op, String table_name,
                                   String key, String[] condition_sourceTable, String[] condition_source){
        if (condition_source != null) {
            for (int index = 0; index < condition_source.length; index++) {
                if (table_name.equals(condition_sourceTable[index]) && key.equals(condition_source[index]))
                    continue;
                Object history = null;
                if (FaultToleranceRelax.isHistoryView) {
                    history = this.loggingManager.inspectDependencyView(groupId, table_name, key, condition_source[index], op.bid);
                }
                if (history != null) {
                    op.historyView = history;
                } else {
                    if (FaultToleranceRelax.isAbortPushDown) {
                        OperationChain OCFromConditionSource = tpg.getOC(condition_sourceTable[index], condition_source[index]);
                        //Add dependent Oc
                        op.incrementPd(OCFromConditionSource);
                        //Add the shadow operations
                        OCFromConditionSource.addOperation(op);
                    }
                }
            }
        }
    }
    private void inspectGSDependency(long groupId, OperationChain curOC, Operation op, String table_name,
                                   String key, String[] condition_sourceTable, String[] condition_source){
        if (condition_source != null) {
            for (int index = 0; index < condition_source.length; index++) {
                if (table_name.equals(condition_sourceTable[index]) && key.equals(condition_source[index]))
                    continue;
                Object history = null;
                if (FaultToleranceRelax.isHistoryView) {
                    history = this.loggingManager.inspectDependencyView(groupId, table_name, key, condition_source[index], op.bid);
                }
                if (history != null) {
                    op.historyView = history;
                } else {
                    if (FaultToleranceRelax.isAbortPushDown) {
                        OperationChain OCFromConditionSource = tpg.getOC(condition_sourceTable[index], condition_source[index]);
                        //Add dependent Oc
                        op.incrementPd(OCFromConditionSource);
                        //Add the shadow operations
                        OCFromConditionSource.addOperation(op);
                    }
                }
            }
        }
    }
    public static int getTaskId(String key, Integer delta) {
        Integer _key = Integer.valueOf(key);
        return _key / delta;
    }
}
