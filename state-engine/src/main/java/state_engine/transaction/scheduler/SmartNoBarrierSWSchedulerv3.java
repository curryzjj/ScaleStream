package state_engine.transaction.scheduler;

import state_engine.common.OperationChain;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

public class SmartNoBarrierSWSchedulerv3 implements IScheduler, OperationChain.IOnDependencyResolvedListener {


    private Object leftOversLock = new Object();
    private OperationChain leftOversHead;
    private OperationChain leftOversTail;
    private OperationChain leftOverCurrent;

    private Object withDependentsLock = new Object();
    private OperationChain withDependentsHead;
    private OperationChain withDependentsTail;
    private OperationChain withDependentsCurrent;

    private AtomicInteger totalSubmitted;
    private AtomicInteger totalProcessed;

    public SmartNoBarrierSWSchedulerv3(int tp) {
        totalSubmitted = new AtomicInteger(0);
        totalProcessed = new AtomicInteger(0);
    }

    @Override
    public void submitOcs(int threadId, Collection<OperationChain> ocs) {

        OperationChain loHead = null;
        OperationChain loTail = null;

        OperationChain wdHead = null;
        OperationChain wdTail = null;

        for (OperationChain oc : ocs) {
            if(!oc.hasDependency() && oc.hasDependents())
                if(loTail!=null) {
                    loTail.next = oc;
                    oc.prev = loTail;
                    loTail = oc;
                } else {
                    loHead = oc;
                    loTail = oc;
                }
            else if(!oc.hasDependency())
                if(wdTail!=null) {
                    wdTail.next = oc;
                    oc.prev = wdTail;
                    wdTail = oc;
                } else {
                    wdHead = oc;
                    wdTail = oc;
                }
            else
                oc.setOnOperationChainChangeListener(this);
        }

        totalSubmitted.addAndGet(ocs.size());

        synchronized (leftOversLock) {
            if (leftOversHead == null) {
                leftOversHead = loHead;
                leftOversTail = loTail;
                leftOverCurrent = leftOversHead;
            } else {
                leftOversTail.next = loHead;
                loHead.prev = leftOversTail;
                leftOversTail = loTail;
            }
        }

        synchronized (withDependentsLock) {
            if (withDependentsHead == null) {
                withDependentsHead = wdHead;
                withDependentsTail = wdTail;
                withDependentsCurrent = withDependentsHead;
            } else {
                withDependentsTail.next = wdHead;
                wdHead.prev = withDependentsTail;
                withDependentsTail = wdTail;
            }
        }
    }

    @Override
    public void onDependencyResolvedListener(int threadId, OperationChain oc) {
        if(oc.hasDependents())
            synchronized (withDependentsLock) {
                withDependentsTail.next = oc;
                oc.prev = withDependentsTail;
                withDependentsTail = oc;
                if(withDependentsCurrent==null)
                    withDependentsCurrent = oc;
            }
        else
            synchronized (leftOversLock) {
                leftOversTail.next = oc;
                oc.prev = leftOversTail;
                leftOversTail = oc;
                if(leftOverCurrent==null)
                    leftOverCurrent = oc;
            }
    }

    @Override
    public OperationChain next(int threadId) {
        OperationChain oc = getOcForThreadAndDLevel(threadId);
        while(oc==null) {
            if(areAllOCsScheduled(threadId))
                break;
            oc = getOcForThreadAndDLevel(threadId);
        }
        if(oc!=null)
            totalProcessed.incrementAndGet();
        return oc;
    }

    protected OperationChain getOcForThreadAndDLevel(int threadId) {
        OperationChain oc = null;

        synchronized (withDependentsLock) {
            if(withDependentsCurrent!=null) {
                oc = withDependentsCurrent;
                withDependentsCurrent = withDependentsCurrent.next;
            }
        }

        if(oc==null) {
            synchronized (leftOversLock) {
                if(leftOverCurrent!=null) {
                    oc = leftOverCurrent;
                    leftOverCurrent = leftOverCurrent.next;
                }
            }
        }


        return oc;
    }

    @Override
    public boolean areAllOCsScheduled(int threadId) {
        return totalProcessed.get()==totalSubmitted.get();
    }

    @Override
    public void reSchedule(int threadId, OperationChain oc) {

    }

    @Override
    public boolean isReSchedulingEnabled() {
        return false;
    }


    @Override
    public void reset() {
        leftOversHead = null;
        leftOversTail = null;
        leftOverCurrent = null;

        withDependentsHead = null;
        withDependentsTail = null;
        withDependentsCurrent = null;

        totalSubmitted = new AtomicInteger(0);
        totalProcessed = new AtomicInteger(0);
    }

}
