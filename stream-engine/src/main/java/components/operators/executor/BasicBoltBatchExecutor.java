package components.operators.executor;

import components.context.TopologyContext;
import components.operators.api.AbstractBolt;
import db.DatabaseException;
import execution.runtime.collector.OutputCollector;
import execution.runtime.tuple.JumboTuple;
import execution.runtime.tuple.impl.Marker;
import execution.runtime.tuple.impl.Tuple;

import java.util.Map;
import java.util.concurrent.BrokenBarrierException;

public class BasicBoltBatchExecutor extends BoltExecutor {
    private static final long serialVersionUID = 5928745739657994175L;
    private final AbstractBolt _op;

    public BasicBoltBatchExecutor(AbstractBolt op) {
        super(op);
        _op = op;
    }

    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        super.prepare(stormConf, context, collector);
    }

    @Override
    public void cleanup() {
        _op.cleanup();
    }

    public void callback(int callee, Marker marker) {
        _op.callback(callee, marker);
    }

    public void execute(JumboTuple in) throws InterruptedException, DatabaseException, BrokenBarrierException {
        _op.execute(in);
    }

    @Override
    public void execute(Tuple in) throws InterruptedException, DatabaseException, BrokenBarrierException {
        _op.execute(in);
    }
}
