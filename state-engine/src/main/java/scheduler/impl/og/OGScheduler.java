package scheduler.impl.og;


import durability.logging.LoggingEntry.LogRecord;
import durability.logging.LoggingStrategy.ImplLoggingManager.*;
import durability.logging.LoggingStrategy.LoggingManager;
import durability.struct.Logging.DependencyLog;
import durability.struct.Logging.HistoryLog;
import durability.struct.Logging.LVCLog;
import durability.struct.Logging.NativeCommandLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import profiler.MeasureTools;
import scheduler.Request;
import scheduler.context.og.OGSchedulerContext;
import scheduler.impl.IScheduler;
import scheduler.struct.MetaTypes;
import scheduler.struct.og.Operation;
import scheduler.struct.og.OperationChain;
import scheduler.struct.og.TaskPrecedenceGraph;
import storage.SchemaRecord;
import storage.TableRecord;
import storage.datatype.DataBox;
import storage.datatype.DoubleDataBox;
import storage.datatype.IntDataBox;
import transaction.function.AVG;
import transaction.function.DEC;
import transaction.function.INC;
import transaction.function.SUM;
import transaction.impl.ordered.MyList;
import utils.AppConfig;
import utils.SOURCE_CONTROL;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static common.constants.TPConstants.Constant.MAX_INT;
import static common.constants.TPConstants.Constant.MAX_SPEED;
import static content.common.CommonMetaTypes.AccessType.*;
import static utils.FaultToleranceConstants.*;

public abstract class OGScheduler<Context extends OGSchedulerContext> implements IScheduler<Context> {
    private static final Logger log = LoggerFactory.getLogger(OGScheduler.class);
    public final int delta;//range of each partition. depends on the number of op in the stage.
    public final TaskPrecedenceGraph<Context> tpg; // TPG to be maintained in this global instance.
    public LoggingManager loggingManager; // Used by fault tolerance
    public int isLogging;// Used by fault tolerance
    protected OGScheduler(int totalThreads, int NUM_ITEMS, int app) {
        delta = (int) Math.ceil(NUM_ITEMS / (double) totalThreads); // Check id generation in DateGenerator.
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
        } else if (loggingManager instanceof LSNVectorLoggingManager) {
            isLogging = LOGOption_lv;
        } else if (loggingManager instanceof DependencyLoggingManager){
            isLogging = LOGOption_dependency;
        } else if (loggingManager instanceof CommandLoggingManager) {
            isLogging = LOGOption_command;
        } else {
            isLogging = LOGOption_no;
        }
        this.tpg.isLogging = this.isLogging;
    }

    /**
     * state to thread mapping
     *
     * @param key
     * @param delta
     * @return
     */
    public static int getTaskId(String key, Integer delta) {
        Integer _key = Integer.valueOf(key);
        return _key / delta;
    }

    public Context getTargetContext(TableRecord d_record) {
        // the thread to submit the operation may not be the thread to execute it.
        // we need to find the target context this thread is mapped to.
        int threadId = getTaskId(d_record.record_.GetPrimaryKey(), delta);
        return tpg.threadToContextMap.get(threadId);
    }

    public Context getTargetContext(String key) {
        // the thread to submit the operation may not be the thread to execute it.
        // we need to find the target context this thread is mapped to.
        int threadId =  Integer.parseInt(key) / delta;
        return tpg.threadToContextMap.get(threadId);
    }


    public void start_evaluation(Context context, long mark_ID, int num_events) {
        int threadId = context.thisThreadId;
        INITIALIZE(context);

        do {
//            MeasureTools.BEGIN_SCHEDULE_EXPLORE_TIME_MEASURE(threadId);
            EXPLORE(context);
//            MeasureTools.END_SCHEDULE_EXPLORE_TIME_MEASURE(threadId);
//            MeasureTools.BEGIN_SCHEDULE_USEFUL_TIME_MEASURE(threadId);
            PROCESS(context, mark_ID);
//            MeasureTools.END_SCHEDULE_USEFUL_TIME_MEASURE(threadId);
//            MeasureTools.END_SCHEDULE_EXPLORE_TIME_MEASURE(threadId);
        } while (!FINISHED(context));
        RESET(context);//

//        MeasureTools.SCHEDULE_TIME_RECORD(threadId, num_events);
    }

    /**
     * Transfer event processing
     *
     * @param operation
     * @param previous_mark_ID
     * @param clean
     */
    protected void Transfer_Fun(Operation operation, long previous_mark_ID, boolean clean) {
        MeasureTools.BEGIN_SCHEDULE_USEFUL_TIME_MEASURE(operation.context.thisThreadId);
        int success = operation.success[0];
        SchemaRecord preValues = operation.condition_records[0].content_.readPreValues(operation.bid);
        final long sourceAccountBalance = preValues.getValues().get(1).getLong();
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
                operation.success[0] ++;
            }
        }
        if (operation.record_ref != null) {
            operation.record_ref.setRecord(operation.d_record.content_.readPreValues(operation.bid));//read the resulting tuple.
        }
        if (operation.success[0] == success) {
            operation.isFailed = true;
        }
        MeasureTools.END_SCHEDULE_USEFUL_TIME_MEASURE(operation.context.thisThreadId);
        if (!operation.isFailed) {
            if (isLogging == LOGOption_path && !operation.pKey.equals(preValues.GetPrimaryKey()) && !operation.isCommit) {
                MeasureTools.BEGIN_SCHEDULE_TRACKING_TIME_MEASURE(operation.context.thisThreadId);
                int id = getTaskId(operation.pKey, delta);
                this.loggingManager.addLogRecord(new HistoryLog(id, operation.table_name, operation.pKey, preValues.GetPrimaryKey(), operation.bid, sourceAccountBalance));
                operation.isCommit = true;
                MeasureTools.END_SCHEDULE_TRACKING_TIME_MEASURE(operation.context.thisThreadId);
            }
        }
    }

    /**
     * Deposite event processing
     *
     * @param operation
     * @param mark_ID
     * @param clean
     */
    protected void Depo_Fun(Operation operation, long mark_ID, boolean clean) {
        MeasureTools.BEGIN_SCHEDULE_USEFUL_TIME_MEASURE(operation.context.thisThreadId);
        SchemaRecord srcRecord = operation.s_record.content_.readPreValues(operation.bid);
        List<DataBox> values = srcRecord.getValues();
        AppConfig.randomDelay();
        //apply function to modify..
        SchemaRecord tempo_record;
        tempo_record = new SchemaRecord(values);//tempo record
        tempo_record.getValues().get(1).incLong(operation.function.delta_long);//compute.
        operation.s_record.content_.updateMultiValues(operation.bid, mark_ID, clean, tempo_record);//it may reduce NUMA-traffic.
        MeasureTools.END_SCHEDULE_USEFUL_TIME_MEASURE(operation.context.thisThreadId);
    }

    protected void GrepSum_Fun(Operation operation, long previous_mark_ID, boolean clean) {
        MeasureTools.BEGIN_SCHEDULE_USEFUL_TIME_MEASURE(operation.context.thisThreadId);
        int success = operation.success[0];
        int keysLength = operation.condition_records.length;
        SchemaRecord[] preValues = new SchemaRecord[operation.condition_records.length];
        long sum = 0;
        AppConfig.randomDelay();
        for (int i = 0; i < keysLength; i++) {
            preValues[i] = operation.condition_records[i].content_.readPreValues(operation.bid);
            sum += preValues[i].getValues().get(1).getLong();
        }
        sum /= keysLength;
        if (operation.function.delta_long != -1) {
            SchemaRecord srcRecord = operation.s_record.content_.readPreValues(operation.bid);
            SchemaRecord tempo_record = new SchemaRecord(srcRecord);//tempo record
            if (operation.function instanceof SUM) {
                tempo_record.getValues().get(1).setLong(sum);//compute.
            } else
                throw new UnsupportedOperationException();
            operation.d_record.content_.updateMultiValues(operation.bid, previous_mark_ID, clean, tempo_record);//it may reduce NUMA-traffic.
            synchronized (operation.success) {
                operation.success[0]++;
            }
        }
        if (operation.record_ref != null) {
            operation.record_ref.setRecord(operation.d_record.content_.readPreValues(operation.bid));//read the resulting tuple.
        }
        if (operation.success[0] == success) {
            operation.isFailed = true;
        }
        MeasureTools.END_SCHEDULE_USEFUL_TIME_MEASURE(operation.context.thisThreadId);
        if (!operation.isFailed) {
            if (isLogging == LOGOption_path && !operation.isCommit) {
                for (int i = 0; i < keysLength; i++) {
                    if (!operation.pKey.equals(operation.condition_records[i].record_.GetPrimaryKey())) {
                        MeasureTools.BEGIN_SCHEDULE_TRACKING_TIME_MEASURE(operation.context.thisThreadId);
                        int id = getTaskId(operation.pKey, delta);
                        this.loggingManager.addLogRecord(new HistoryLog(id, operation.table_name, operation.pKey, operation.condition_records[i].record_.GetPrimaryKey(), operation.bid, sum));
                        operation.isCommit = true;
                        MeasureTools.END_SCHEDULE_TRACKING_TIME_MEASURE(operation.context.thisThreadId);
                    }
                }
            }
        }
    }
    protected void TollProcess_Fun(Operation operation, long previous_mark_ID, boolean clean) {
        MeasureTools.BEGIN_SCHEDULE_USEFUL_TIME_MEASURE(operation.context.thisThreadId);
        int success = operation.success[0];
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
                synchronized (operation.success) {
                    operation.success[0] ++;
                }
            }
        } else {
            if (operation.function.delta_int < MAX_INT) {
                HashSet cnt_segment = srcRecord.get(1).getHashSet();
                cnt_segment.add(operation.function.delta_int);//update hashset; updated state also. TODO: be careful of this.
                operation.record_ref.setRecord(new SchemaRecord(new IntDataBox(cnt_segment.size())));//return updated record.
                synchronized (operation.success) {
                    operation.success[0] ++;
                }
            }
        }
        if (operation.success[0] == success) {
            operation.isFailed = true;
        }
        MeasureTools.END_SCHEDULE_USEFUL_TIME_MEASURE(operation.context.thisThreadId);
    }

    /**
     * general operation execution entry method for all schedulers.
     *
     * @param operation
     * @param mark_ID
     * @param clean
     */
    public void execute(Operation operation, long mark_ID, boolean clean) {
        if (operation.getOperationState().equals(MetaTypes.OperationStateType.ABORTED)) {
            commitLog(operation);
            return; // return if the operation is already aborted
        }
        if (operation.accessType.equals(READ_WRITE_COND_READ)) {
            Transfer_Fun(operation, mark_ID, clean);
        } else if (operation.accessType.equals(READ_WRITE_COND)) {
            if (this.tpg.getApp() == 1) {//SL
                Transfer_Fun(operation, mark_ID, clean);
            } else {//OB
                AppConfig.randomDelay();
                List<DataBox> d_record = operation.condition_records[0].content_.ReadAccess(operation.bid, mark_ID, clean, operation.accessType).getValues();
                long askPrice = d_record.get(1).getLong();//price
                long left_qty = d_record.get(2).getLong();//available qty;
                long bidPrice = operation.condition.arg1;
                long bid_qty = operation.condition.arg2;
                if (bidPrice > askPrice || bid_qty < left_qty) {
                    d_record.get(2).setLong(left_qty - operation.function.delta_long);//new quantity.
                    operation.success[0] ++;
                }
            }
        } else if (operation.accessType.equals(READ_WRITE)) {
            if (this.tpg.getApp() == 1) { //SL
                Depo_Fun(operation, mark_ID, clean);
            } else {
                AppConfig.randomDelay();
                SchemaRecord srcRecord = operation.s_record.content_.ReadAccess(operation.bid,mark_ID,clean,operation.accessType);
                List<DataBox> values = srcRecord.getValues();
                if (operation.function instanceof INC) {
                    values.get(2).setLong(values.get(2).getLong() + operation.function.delta_long);
                } else
                    throw new UnsupportedOperationException();
            }
        } else if (operation.accessType.equals(READ_WRITE_COND_READN)) {
            GrepSum_Fun(operation, mark_ID, clean);
        } else if (operation.accessType.equals(READ_WRITE_READ)) {
            assert operation.record_ref != null;
            if (this.tpg.getApp() == 2)
                TollProcess_Fun(operation, mark_ID, clean);
        } else if (operation.accessType.equals(WRITE_ONLY)) {
            //OB-Alert
            AppConfig.randomDelay();
            operation.d_record.record_.getValues().get(1).setLong(operation.value);
        } else {
            throw new UnsupportedOperationException();
        }
        commitLog(operation);
    }

    @Override
    public void PROCESS(Context context, long mark_ID) {
        int threadId = context.thisThreadId;
        MeasureTools.BEGIN_SCHEDULE_NEXT_TIME_MEASURE(context.thisThreadId);
        OperationChain next = next(context);
        MeasureTools.END_SCHEDULE_NEXT_TIME_MEASURE(threadId);

        if (next != null) {
            if (executeWithBusyWait(context, next, mark_ID)) { // only when executed, the notification will start.
                MeasureTools.BEGIN_NOTIFY_TIME_MEASURE(threadId);
                NOTIFY(next, context);
                MeasureTools.END_NOTIFY_TIME_MEASURE(threadId);
            }
        } else {
                MeasureTools.BEGIN_SCHEDULE_NEXT_TIME_MEASURE(context.thisThreadId);
                next = nextFromBusyWaitQueue(context);
                MeasureTools.END_SCHEDULE_NEXT_TIME_MEASURE(threadId);
                if (next != null) {
                    if (executeWithBusyWait(context, next, mark_ID)) { // only when executed, the notification will start.
                        MeasureTools.BEGIN_NOTIFY_TIME_MEASURE(threadId);
                        NOTIFY(next, context);
                        MeasureTools.END_NOTIFY_TIME_MEASURE(threadId);
                    }
                }
        }
    }

    /**
     * Try to get task from local queue.
     *
     * @param context
     * @return
     */
    protected OperationChain next(Context context) {
        throw new UnsupportedOperationException();
    }

    public boolean executeWithBusyWait(Context context, OperationChain operationChain, long mark_ID) {
        MyList<Operation> operation_chain_list = operationChain.getOperations();
        for (Operation operation : operation_chain_list) {
            if (operation.getOperationState().equals(MetaTypes.OperationStateType.EXECUTED)
                    || operation.getOperationState().equals(MetaTypes.OperationStateType.ABORTED)
                    || operation.isFailed) {
                commitLog(operation);
                continue;
            }
            if (isConflicted(context, operationChain, operation)) {
                return false;
            }
            execute(operation, mark_ID, false);
            if (!operation.isFailed && !operation.getOperationState().equals(MetaTypes.OperationStateType.ABORTED)) {
                operation.stateTransition(MetaTypes.OperationStateType.EXECUTED);
            } else {
                checkTransactionAbort(operation, operationChain);
            }
        }
        return true;
    }

    protected void checkTransactionAbort(Operation operation, OperationChain operationChain) {
        // in coarse-grained algorithms, we will not handle transaction abort gracefully, just update the state of the operation
        operation.stateTransition(MetaTypes.OperationStateType.ABORTED);
        // save the abort information and redo the batch.
    }

    protected OperationChain nextFromBusyWaitQueue(Context context) {
        return context.busyWaitQueue.poll();
    }

    protected abstract void DISTRIBUTE(OperationChain task, Context context);

    protected abstract void NOTIFY(OperationChain task, Context context);

    @Override
    public boolean FINISHED(Context context) {
        return context.finished();
    }

    /**
     * Submit requests to target thread --> data shuffling is involved.
     *
     * @param context
     * @param request
     * @return
     */
    @Override
    public boolean SubmitRequest(Context context, Request request) {
        context.push(request);
        return false;
    }

    @Override
    public void RESET(Context context) {
        MeasureTools.BEGIN_SCHEDULE_WAIT_TIME_MEASURE(context.thisThreadId);
        SOURCE_CONTROL.getInstance().waitForOtherThreads(context.thisThreadId);
        MeasureTools.END_SCHEDULE_WAIT_TIME_MEASURE(context.thisThreadId);
        context.reset();
        tpg.reset(context);
    }

    @Override
    public void TxnSubmitBegin(Context context) {
        context.requests.clear();
    }

    @Override
    public void TxnSubmitFinished(Context context) {
        MeasureTools.BEGIN_TPG_CONSTRUCTION_TIME_MEASURE(context.thisThreadId);
        // the data structure to store all operations created from the txn, store them in order, which indicates the logical dependency
        List<Operation> operationGraph = new ArrayList<>();
        int txnOpId = 0;
        Operation headerOperation = null;
        Operation set_op;
        for (Request request : context.requests) {
             set_op = constructOp(operationGraph, request);
            if (txnOpId == 0)
                headerOperation = set_op;
            // addOperation an operation id for the operation for the purpose of temporal dependency construction
            set_op.setTxnOpId(txnOpId++);
            set_op.addHeader(headerOperation);
            headerOperation.addDescendant(set_op);
        }
        // set logical dependencies among all operation in the same transaction
        MeasureTools.END_TPG_CONSTRUCTION_TIME_MEASURE(context.thisThreadId);
    }

    private Operation constructOp(List<Operation> operationGraph, Request request) {
        long bid = request.txn_context.getBID();
        Operation set_op;
        Context targetContext = getTargetContext(request.src_key);
        switch (request.accessType) {
            case WRITE_ONLY:
                set_op = new Operation(request.src_key, null, request.table_name, null, null, null,
                        null, request.txn_context, request.accessType, null, request.d_record, bid, targetContext);
                set_op.value = request.value;
                break;
            case READ_WRITE_COND: // they can use the same method for processing
            case READ_WRITE:
                set_op = new Operation(request.src_key, request.function, request.table_name, null, request.condition_records, request.condition,
                        request.success, request.txn_context, request.accessType, request.d_record, request.d_record, bid, targetContext);
                break;
            case READ_WRITE_COND_READ:
            case READ_WRITE_COND_READN:
                set_op = new Operation(request.src_key, request.function, request.table_name, request.record_ref, request.condition_records, request.condition,
                        request.success, request.txn_context, request.accessType, request.d_record, request.d_record, bid, targetContext);
                break;
            case READ_WRITE_READ:
                set_op = new Operation(request.src_key, request.function, request.table_name, request.record_ref, null, null,
                        request.success, request.txn_context, request.accessType, request.d_record, request.d_record, bid, targetContext);
                break;
            default:
                throw new RuntimeException("Unexpected operation");
        }
        operationGraph.add(set_op);
        tpg.setupOperationTDFD(set_op, request, targetContext);
        return set_op;
    }

    @Override
    public void AddContext(int threadId, Context context) {
        tpg.threadToContextMap.put(threadId, context);
        tpg.setOCs(context);
    }

    protected boolean isConflicted(Context context, OperationChain operationChain, Operation operation) {
        if (operation.fd_parents != null) {
            for (Operation conditioned_operation : operation.fd_parents) {
                if (conditioned_operation != null) {
                    if (!(conditioned_operation.getOperationState().equals(MetaTypes.OperationStateType.EXECUTED)
                            || conditioned_operation.getOperationState().equals(MetaTypes.OperationStateType.ABORTED)
                            || conditioned_operation.isFailed)) {
                        // blocked and busy wait
                        context.busyWaitQueue.add(operationChain);
                        return true;
                    }
                }
            }
        }
        return false;
    }
    private void commitLog(Operation operation) {
        if (operation.isCommit) {
            return;
        }
        if (isLogging == LOGOption_path) {
            return;
        }
        MeasureTools.BEGIN_SCHEDULE_TRACKING_TIME_MEASURE(operation.context.thisThreadId);
        if (isLogging == LOGOption_wal) {
            ((LogRecord) operation.logRecord).addUpdate(operation.d_record.content_.readPreValues(operation.bid));
            this.loggingManager.addLogRecord(operation.logRecord);
        } else if (isLogging == LOGOption_dependency) {
            ((DependencyLog) operation.logRecord).setId(operation.bid + "." + operation.getTxnOpId());
            for (Operation op : operation.fd_parents) {
                ((DependencyLog) operation.logRecord).addInEdge(op.bid + "." + op.getTxnOpId());
            }
            for (Operation op : operation.fd_children) {
                ((DependencyLog) operation.logRecord).addOutEdge(op.bid + "." + op.getTxnOpId());
            }
            Operation ldParent = operation.getOC().getOperations().lower(operation);
            if (ldParent != null)
                ((DependencyLog) operation.logRecord).addInEdge(ldParent.bid + "." + ldParent.getTxnOpId());
            Operation ldChild = operation.getOC().getOperations().higher(operation);
            if (ldChild != null)
                ((DependencyLog) operation.logRecord).addOutEdge(ldChild.bid + "." + ldChild.getTxnOpId());
            this.loggingManager.addLogRecord(operation.logRecord);
        } else if (isLogging == LOGOption_lv) {
            ((LVCLog) operation.logRecord).setAccessType(operation.accessType);
            ((LVCLog) operation.logRecord).setThreadId(operation.context.thisThreadId);
            this.loggingManager.addLogRecord(operation.logRecord);
        } else if (isLogging == LOGOption_command) {
            ((NativeCommandLog) operation.logRecord).setId(operation.bid + "." + operation.getTxnOpId());
            this.loggingManager.addLogRecord(operation.logRecord);
        }
        operation.isCommit = true;
        MeasureTools.END_SCHEDULE_TRACKING_TIME_MEASURE(operation.context.thisThreadId);
    }

}
