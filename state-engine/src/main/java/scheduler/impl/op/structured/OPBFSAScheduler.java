package scheduler.impl.op.structured;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import profiler.MeasureTools;
import scheduler.context.op.OPSAContext;
import scheduler.struct.MetaTypes;
import scheduler.struct.op.Operation;
import utils.SOURCE_CONTROL;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import static common.CONTROL.enable_log;
import static java.lang.Integer.min;
import static profiler.MeasureTools.BEGIN_SCHEDULE_ABORT_TIME_MEASURE;
import static profiler.MeasureTools.END_SCHEDULE_ABORT_TIME_MEASURE;
import static utils.FaultToleranceConstants.LOGOption_path;

public class OPBFSAScheduler<Context extends OPSAContext> extends OPBFSScheduler<Context> {
    private static final Logger LOG = LoggerFactory.getLogger(OPBFSAScheduler.class);

    public final ConcurrentLinkedDeque<Operation> failedOperations = new ConcurrentLinkedDeque<>();//aborted operations per thread.
    public final AtomicBoolean needAbortHandling = new AtomicBoolean(false);//if any operation is aborted during processing.
    public int targetRollbackLevel = 0;//shared data structure.

    public OPBFSAScheduler(int totalThreads, int NUM_ITEMS, int app) {
        super(totalThreads, NUM_ITEMS, app);
    }

    /**
     * // O1 -> (logical)  O2
     * // T1: pickup O1. Transition O1 (ready - > execute) || notify O2 (speculative -> ready).
     * // T2: pickup O2 (speculative -> executed)
     * // T3: pickup O2
     * fast explore dependencies in TPG and put ready/speculative operations into task queues.
     *
     * @param context
     */
    @Override
    public void EXPLORE(Context context) {
        Operation next = Next(context);
        while (next == null && !context.finished()) {
            SOURCE_CONTROL.getInstance().waitForOtherThreads(context.thisThreadId);
            END_SCHEDULE_ABORT_TIME_MEASURE(context.thisThreadId);//if it needs abort, then the time spent on following level is abort time
            if (needAbortHandling.get()) {
                //TODO: also we can tracking abort bid here
                BEGIN_SCHEDULE_ABORT_TIME_MEASURE(context.thisThreadId);
                if (enable_log) LOG.debug("check abort: " + context.thisThreadId + " | " + needAbortHandling.get());
                abortHandling(context);
            }
            ProcessedToNextLevel(context);
            SOURCE_CONTROL.getInstance().waitForOtherThreads(context.thisThreadId);
            next = Next(context);
        }
        if (context.finished()) {
            SOURCE_CONTROL.getInstance().waitForOtherThreads(context.thisThreadId);
            END_SCHEDULE_ABORT_TIME_MEASURE(context.thisThreadId);//if it needs abort, then the time spent on following level is abort time
            if (needAbortHandling.get()) {
                BEGIN_SCHEDULE_ABORT_TIME_MEASURE(context.thisThreadId);
                if (enable_log)
                    LOG.debug("aborted after all ocs explored: " + context.thisThreadId + " | " + needAbortHandling.get());
                abortHandling(context);
                ProcessedToNextLevel(context);
                SOURCE_CONTROL.getInstance().waitForOtherThreads(context.thisThreadId);
                next = Next(context);
            }
        }

        DISTRIBUTE(next, context);
    }

    @Override
    public void PROCESS(Context context, long mark_ID) {
        int cnt = 0;
        int batch_size = 100;//TODO;
        int threadId = context.thisThreadId;
        MeasureTools.BEGIN_SCHEDULE_NEXT_TIME_MEASURE(context.thisThreadId);

        do {
            Operation next = next(context);
            if (next == null) {
                break;
            }
            context.batchedOperations.push(next);
            cnt++;
            if (cnt > batch_size) {
                break;
            }
        } while (true);
        MeasureTools.END_SCHEDULE_NEXT_TIME_MEASURE(threadId);
        for (Operation operation : context.batchedOperations) {
            execute(operation, mark_ID, false);
        }

        while (context.batchedOperations.size() != 0) {
            Operation remove = context.batchedOperations.remove();
            MeasureTools.BEGIN_NOTIFY_TIME_MEASURE(threadId);
            NOTIFY(remove, context);
            MeasureTools.END_NOTIFY_TIME_MEASURE(threadId);
            checkTransactionAbort(remove);
        }
    }

    protected void checkTransactionAbort(Operation operation) {
        if (operation.isFailed && !operation.getOperationState().equals(MetaTypes.OperationStateType.ABORTED)) {
            needAbortHandling.compareAndSet(false, true);
            failedOperations.push(operation); // operation need to wait until the last abort has completed
        }
    }

    protected void abortHandling(Context context) {
        MarkOperationsToAbort(context);

        SOURCE_CONTROL.getInstance().waitForOtherThreads(context.thisThreadId);
        IdentifyRollbackLevel(context);
        SOURCE_CONTROL.getInstance().waitForOtherThreads(context.thisThreadId);
        SetRollbackLevel(context);

        RollbackToCorrectLayerForRedo(context);
        ResumeExecution(context);
    }

    //TODO: mark operations of aborted transaction to be aborted.
    protected void MarkOperationsToAbort(Context context) {
        boolean markAny = false;
        ArrayList<Operation> operations;
        int curLevel;
        for (Map.Entry<Integer, ArrayList<Operation>> operationsEntry: context.allocatedLayeredOCBucket.entrySet()) {
            operations = operationsEntry.getValue();
            curLevel = operationsEntry.getKey();
            for (Operation operation : operations) {
                markAny |= _MarkOperationsToAbort(context, operation);
            }
            if (markAny && context.rollbackLevel == -1) { // current layer contains operations to abort, try to abort this layer.
                context.rollbackLevel = curLevel;
            }
        }
        if (context.rollbackLevel == -1 || context.rollbackLevel > context.currentLevel) { // the thread does not contain aborted operations
            context.rollbackLevel = context.currentLevel;
        }
        context.isRollbacked = true;
        if (enable_log) LOG.debug("++++++ rollback at level: " + context.thisThreadId + " | " + context.rollbackLevel);
    }

    /**
     * Mark operations of an aborted transaction to abort.
     *
     * @param context
     * @param operation
     * @return
     */
    private boolean _MarkOperationsToAbort(Context context, Operation operation) {
        long bid = operation.bid;
        boolean markAny = false;
        for (Operation failedOp : failedOperations) {
            if (bid == failedOp.bid) { //identify bids to be aborted (abort operation in one state transaction).
                operation.stateTransition(MetaTypes.OperationStateType.ABORTED);
                if (this.isLogging == LOGOption_path && operation.txnOpId == 0) {
                    MeasureTools.BEGIN_SCHEDULE_TRACKING_TIME_MEASURE(context.thisThreadId);
                    this.tpg.threadToPathRecord.get(context.thisThreadId).addAbortBid(operation.bid);
                    MeasureTools.END_SCHEDULE_TRACKING_TIME_MEASURE(context.thisThreadId);
                }
                markAny = true;
            }
        }
        return markAny;
    }

    protected void IdentifyRollbackLevel(Context context) {
        if (context.thisThreadId == 0) {
            targetRollbackLevel = Integer.MAX_VALUE;
            for (int i = 0; i < tpg.totalThreads; i++) { // find the first level that contains aborted operations
                targetRollbackLevel = min(targetRollbackLevel, tpg.threadToContextMap.get(i).rollbackLevel);
            }
        }
    }

    protected void SetRollbackLevel(Context context) {
        if (enable_log) LOG.debug("++++++ rollback at: " + targetRollbackLevel);
        context.rollbackLevel = targetRollbackLevel;
    }

    protected void ResumeExecution(Context context) {
        context.rollbackLevel = -1;
        context.isRollbacked = false;

        SOURCE_CONTROL.getInstance().waitForOtherThreads(context.thisThreadId);
        if (needAbortHandling.compareAndSet(true, false)) {
            failedOperations.clear();
        }
    }

    protected void RollbackToCorrectLayerForRedo(Context context) {
        int level;
        for (level = context.rollbackLevel; level <= context.currentLevel; level++) {
            context.scheduledOPs -= getNumOPsByLevel(context, level);
        }
        context.currentLevelIndex = 0;
        // it needs to rollback to the level -1, because aborthandling has immediately followed up with ProcessedToNextLevel
        context.currentLevel = context.rollbackLevel - 1;
    }

    protected int getNumOPsByLevel(Context context, int level) {
        int ops = 0;
        if (context.allocatedLayeredOCBucket.containsKey(level)) { // oc level may not be sequential
            ops += context.allocatedLayeredOCBucket.get(level).size();
        }
        return ops;
    }
}