package execution.runtime;
import common.collections.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import components.context.TopologyContext;
import components.operators.executor.BasicSpoutBatchExecutor;
import execution.ExecutionNode;
import execution.runtime.collector.OutputCollector;
import state_engine.Clock;
import state_engine.DatabaseException;

import java.util.HashMap;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;

import static common.CONTROL.combo_bid_size;
import static common.CONTROL.enable_app_combo;
import static state_engine.content.Content.*;
/**
 * Task thread that hosts spout logic.
 */
public class spoutThread extends executorThread {
    private static final Logger LOG = LoggerFactory.getLogger(spoutThread.class);
    private final BasicSpoutBatchExecutor sp;
    private final int loadTargetHz;
    private final int timeSliceLengthMs;
    private final OutputCollector collector;
    int _combo_bid_size = 1;
    /**
     * @param e                 :                  Each thread corresponds to one executionNode.
     * @param conf
     * @param cpu
     * @param node
     * @param latch
     * @param loadTargetHz
     * @param timeSliceLengthMs
     * @param threadMap
     * @param clock
     */
    public spoutThread(ExecutionNode e, TopologyContext context, Configuration conf, long[] cpu,
                       int node, CountDownLatch latch, int loadTargetHz, int timeSliceLengthMs,
                       HashMap<Integer, executorThread> threadMap, Clock clock) {
        super(e, conf, context, cpu, node, latch, threadMap);
        this.sp = (BasicSpoutBatchExecutor) e.op;
        this.loadTargetHz = loadTargetHz;
        this.timeSliceLengthMs = timeSliceLengthMs;
        this.collector = new OutputCollector(e, context);
        batch = conf.getInt("batch", 100);
        sp.setExecutionNode(e);
        sp.setclock(clock);
        switch (conf.getInt("CCOption", 0)) {
            case CCOption_OrderLOCK://Ordered lock_ratio
            case CCOption_LWM://LWM
            case CCOption_SStore://SStore
                _combo_bid_size = 1;
                break;
            default:
                _combo_bid_size = combo_bid_size;
        }
    }
    @Override
    protected void _execute_noControl() throws InterruptedException {
        sp.bulk_emit(batch);
        if (enable_app_combo)
            cnt += batch * _combo_bid_size;
        else
            cnt += batch;
    }
    protected void _execute() throws InterruptedException {

        _execute_noControl();

    }
    @Override
    public void run() {
        try {

            Thread.currentThread().setName("Operator:" + executor.getOP() + "\tExecutor ID:" + executor.getExecutorID());
            initilize_queue(this.executor.getExecutorID());
            //do Loading
            sp.prepare(conf, context, collector);
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            LOG.info("Operator:\t" + executor.getOP_full() + " is ready");
            this.Ready(LOG);//Tell executor thread to proceed.
            latch.countDown();          //tells others I'm really ready.
            try {
                latch.await();
            } catch (InterruptedException ignored) {
            }
            routing();
        } catch (InterruptedException | DatabaseException | BrokenBarrierException e) {
            e.printStackTrace();
        } finally {
            this.executor.display();
            double expected_throughput = 0;
            if (end_emit == 0) {
                end_emit = System.nanoTime();
            }
            double actual_throughput = (cnt - this.executor.op.getEmpty()) * 1E6 / (end_emit - start_emit);

            LOG.info(this.executor.getOP_full()
                            + "\tfinished execution and exit with throughput (k input_event/s) of:\t"
                            + actual_throughput + "(" + actual_throughput / expected_throughput + ")"
                            + " on node: " + node
//					+ " ( " + Arrays.show(cpu) + ")"
            );
//            LOG.info("== Spout Busy time: " + busy_time + "\t Sleep time: " + sleep_time +" ==");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                //e.printStackTrace();
            }
        }

    }
}