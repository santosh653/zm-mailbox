/*
 * ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1
 * 
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 ("License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.zimbra.com/license
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 * 
 * The Original Code is: Zimbra Collaboration Suite Server.
 * 
 * The Initial Developer of the Original Code is Zimbra, Inc.
 * Portions created by Zimbra are Copyright (C) 2006 Zimbra, Inc.
 * All Rights Reserved.
 * 
 * Contributor(s):
 * 
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.operation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.service.ServiceException;

/**
 * 
 *
 */
public class Scheduler {
	/**
	 * The Priority of the operation sets the basic order in which 
	 * operations are executed.  All other things being equal, the higher
	 * priority operation will always be executed first.
	 * <p>
	 *  Note that like many Priority implementations (e.g. Unix priorities) 
	 *  the "higher priority" has the lower integral "level", with a priority
	 *  level of 0 meaning "most important".  
	 * <p>
	 *  The priorities are:<ul>
	 *    <li>ADMIN - Admin Commands (highest possible priority)</li>
	 *    <li>INTERACTIVE_HIGH - Ops with an expectation of immediate response (e.g. "Search")</li>
	 *    <li>INTERACTIVE_LOW - Ops with a slightly lower expectation (e.g. "Empty Trash")</li>
	 *    <li>BATCH - Ops which can take a little time to execute (e.g. "IMAP Select Folder")</li>
	 *    <li> LOW - Ops which can run slowly (e.g. "ReIndex")</li>
	 *  </ul>  
	 */
	public static enum Priority {
		ADMIN(0),
		INTERACTIVE_HIGH(1),
		INTERACTIVE_LOW(2),
		BATCH(3),
		LOW(4);
		
		private int mLevel;
		
		static int NUM_PRIORITIES() {
			return LOW.getLevel()+1;
		}
		
		private Priority(int level) { 
			mLevel = level;
		}
		
		private int getLevel() {
			return mLevel;
		}
		
		/**
		 * @return the next MORE IMPORTANT priority (ie the one with the lower "level")
		 */
		public Priority increment() {
			switch (mLevel) {
				case 0: return ADMIN;
				case 1: return ADMIN;
				case 2: return INTERACTIVE_HIGH;
				case 3: return INTERACTIVE_LOW;
				case 4: return BATCH;
			}
			assert(false);
			return null;
		}
		
		/**
		 * @return the next LESS IMPORTANT priority (ie the one with the higher "level")
		 */
		public Priority decrement() {
			switch (mLevel) {
				case 0: return INTERACTIVE_HIGH;
				case 1: return INTERACTIVE_LOW;
				case 2: return BATCH;
				case 3: return LOW;
				case 4: return LOW;
			}
			assert(false);
			return null;
		}
		
	}
	
	/**
	 * class MyOp implements IOperation {
	 *     
	 *  		public String getType()          { return type }
	 *  		public Priority getPriority() { return priority }
	 *  		public int getLoad()      { return mOp.getLoad(); }
	 *
	 *      void doIt(parameters) {
	 *          Scheduler.schedule(this);
	 *           try {
	 *              // ...do operation here...
	 *           } finally {
	 *               Scheduler.runCompleted(this);
	 *           }
	 *      }
	 * }
	 */
	public static interface IOperation 
	{
		String getName();  
		Priority getPriority();
		int getLoad();
  	}
	
	
	/**
	 * This API will block the calling thread until the operation can be run.  On return from
	 * this function, the scheduler considers the operation as "running" until the
	 * runCompleted() API is called for the same operation.  It is <b>imperative</b> that 
	 * runCompleted() is called in a try...finally block so that the Scheduler's bookkeeping
	 * does not get out of sync.
	 * 
	 * @param op
	 * @throws ServiceException
	 */
	public void schedule(IOperation op) throws ServiceException {
		mLock.lock();
		try {
			if (opIsRunnable(op, null)) {
				startRunning(op);
			} else {
				ThreadedOperation blockedThread = new BlockedOperation(op, this);
				mOpQueue[op.getPriority().getLevel()].add(blockedThread);
				blockedThread.await();
				assert(mLock.isHeldByCurrentThread());
				assert(!mOpQueue[op.getPriority().getLevel()].contains(op));
			}
		} finally {
			mLock.unlock();
		}
	}
	
	/**
	 * This API updates the Scheduler's internal bookkeeping when the operation has completed.
	 */
	public void runCompleted(IOperation op) {
		mLock.lock();
		try {
			mRunningOperations--;
			if (SPEW) System.out.println("Thread: " + Thread.currentThread().getName() + " runCompleted (1) "+mRunningOperations+" running");
			mCurLoad-=op.getLoad();
			
			do {
				AsyncOperation toRun = getNextRunnableBlockedOperation();
				assert(toRun != op);
				if (toRun == null){
					return;
				}
				startRunning(toRun);
				toRun.signal();
			} while(true);
		} finally {
			mLock.unlock();
		}
	}
	
	
	/**
	 * All requests for a particular mailbox go to the same scheduler, 
	 * This makes sense since just about every object locks the target Mailbox.
	 * 
	 * @param mbox
	 * @return
	 */
	public static Scheduler get(Mailbox mbox) {
		if (mbox == null) {
			return sScheduler[0];
		}
		return sScheduler[mbox.getId() % sScheduler.length]; 
	}

	/**
	 * An alternative to schedule() this api returns TRUE if the operation can run immediately,
	 * or FALSE if the operation cannot be run now.  If FALSE is returned, then AsyncOperation.signal()
	 * will be called by the scheduler() when the operation can be run.  Note that the scheduler may
	 * call signal() from any thread, so it is up to the user to make sure that their particular 
	 * implementation of AsyncOperation.signal() is thread-safe and does the right thing.    
	 *  
	 * @param op
	 * @return TRUE if the operation can run now, FALSE if it must wait
	 * @throws ServiceException
	 */
	public boolean scheduleAsync(AsyncOperation op) throws ServiceException {
		mLock.lock();
		try {
			if (opIsRunnable(op, null)) {
				startRunning(op);
				return true;
			} else {
				mOpQueue[op.getPriority().getLevel()].add(op);
				return false;
			}
		} finally {
			mLock.unlock();
		}
	}
	
	/**
	 * Calling thread doesn't block if the operation cannot be run,
	 * instead calling thread is put on a queue and signal() is called
	 * when the operation is ready to begin.
	 * 
	 * class MyOp implements AsyncOperation {
	 * 
	 *      private storedParams;
	 * 
	 *      boolean doIt(parameters) { 
	 *         storedParams = parameters;
	 *         if (Scheduler.scheduleAsync(this)) {
	 *            try {
	 *                  //...do work...
	 *                  return true;         
	 *            } finally {
	 *               Scheduler.runCompleted(this);
	 *            }
	 *         } else {
	 *             return false;         
	 *         }
	 *      }
	 *      
	 *      void signal() {
	 *          try {
	 *             // ...do work...
	 *          } finally {
	 *             Scheduler.runCompleted(this);
	 *          }
	 *      }
	 * }
	 */
	public static interface AsyncOperation extends IOperation {
		/**
		 * The user code should be run -- either by resuming a sleeping
		 * thread, by immediately running the operation code, or 
		 * something else.
		 * 
		 * Scheduler.runCompleted() *MUST* be called in response
		 * to this function
		 * 
		 *  The implementation of this function should check for an 
		 *  Exception set by the setException() API and deal accordingly 
		 */
		public void signal();
		
		/**
		 * If an exception needs to be raised, this function will
		 * be called *before* signal.  The implemention should
		 * store the exception somewhere and process it
		 *  when signal() is called.
		 * 
		 * @param e
		 */
		public void setException(ServiceException e);
	}
	
	
	/**
	 * Internal class 
	 */
	static abstract class ThreadedOperation implements AsyncOperation {
		volatile protected boolean mSignaled = false;
		volatile protected ServiceException mException = null;
		protected ReentrantLock mLock = null;
		protected Condition mCond = null;
		
		ThreadedOperation(Scheduler sched) {
			mLock = sched.getLock();
			mCond = mLock.newCondition();
		}

		public void await() throws ServiceException {
			assert(mLock.isHeldByCurrentThread());
			
			mSignaled = false;
			do {
				try {
					if (SPEW)  System.out.println(Thread.currentThread().getName()+": Waiting on cond: "+mCond.toString() + " for lock " + mLock);
					mCond.await();
					if (SPEW) System.out.println(Thread.currentThread().getName()+":  EXITING COND:  "+mCond.toString() + " for lock " + mLock);
				} catch (InterruptedException e) {}
			} while (!mSignaled);
			
			if (mException != null)
				throw mException;
		}
		
		public void signal() {
			assert(mLock.isHeldByCurrentThread());
			mSignaled = true;
			assert(mCond != null);
			
			if (SPEW) System.out.println("Signalling on cond: "+mCond.toString());
			mCond.signal();
		}

		public void setException(ServiceException e) {
			mException = e;
		}
		
		public String toString() {
			return "OP("+getName().toString()+","+getPriority()+","+getLoad()+")";
		}
	}
	
	/**
	 * Internal - wrap a generic IOperation so that we can block the c
	 */
	private static class BlockedOperation extends ThreadedOperation {
		
		protected IOperation mOp;
		
		BlockedOperation(IOperation op, Scheduler sched) {
			super(sched);
			mOp = op;
		}

		public String getName()          { return mOp.getName(); }
		public Priority getPriority() { return mOp.getPriority(); }
		public int getLoad()      { return mOp.getLoad(); }
	}


	protected Scheduler(int maxLoad, int targetLoad, int maxOps) {
		mMaxLoad = maxLoad;
		mMaxSimultaneousOperations = maxOps;
		mTargetLoad = targetLoad;
		mCurLoad = 0;
		mRunningOperations = 0;
		
		for (int i = 0; i < Priority.NUM_PRIORITIES(); i++ ){
			mOpQueue[i] = new ArrayList<AsyncOperation>();
		}
	}
	
	/**
	 * Caller is responsible for calling runCompleted() when this run is completed.
	 * 
	 * It *must* be called - so it better be in a finally{} block or something.
	 * 
	 * @param op
	 */
	private void startRunning(IOperation op) {
		assert(mLock.isHeldByCurrentThread());

		if (SPEW) System.out.println("Thread: " + Thread.currentThread().getName() + " startRunning() "+mRunningOperations+" running");
		mRunningOperations++;
		assert(mRunningOperations <= mMaxSimultaneousOperations);
		mCurLoad+=op.getLoad();
		assert(mCurLoad < mMaxLoad);
	}
	
	/**
	 * must be called with mLock held
	 * 
	 * @return
	 */
	private boolean opIsRunnable(IOperation op, List<AsyncOperation> queue) throws ServiceException
	{
		assert(mLock.isHeldByCurrentThread());
		
		/** Check to see if running this op will cause us to use
                 more than the "target max" load , and if this
                 op is not of "Realtime/Interactive" priority also check the
                 count of running operations (yes, "count" is not ideal:
                 see below for discussion) to see if we have too many
                 running at once.
		 */
		if (mRunningOperations == 0) {
			// Don't check load in this case, always all the operation to (try to) run.
			// This just makes life easier -- if the system's max load gets set lower than 
			// one of the operation's load values, then the result will be that the op will run
			// but only while nothing else is happening -- that is a more reasonable result than
			// completely failing to run or throwing an exception 
			if (queue != null) {
				IOperation removed = queue.remove(0);
				if (SPEW) System.out.println("Removing: "+removed.toString());
				assert(removed == op);
			}
			return true;
		} else {
			// skip #running check for highest-level operations, always let them run!
			if  (op.getPriority() == Priority.ADMIN|| mRunningOperations+1 < mMaxSimultaneousOperations) {
				int loadLeft = mTargetLoad - mCurLoad;
				if (op.getLoad() < loadLeft) {
					if (queue != null) {
						IOperation removed = queue.remove(0);
						if (SPEW) System.out.println("Removing: "+removed.toString());
						assert(removed == op);
					}
					return true;
				}
			}
			return false; // can't run current op!
		}
	}

	/**
	 * must be called with mLock held
	 * 
	 * @return
	 */
	private AsyncOperation getNextRunnableBlockedOperation() 
	{
		assert(mLock.isHeldByCurrentThread());
		
		for (Priority pri : Priority.values()) {
			List<AsyncOperation> queue = mOpQueue[pri.getLevel()];
			if (!queue.isEmpty()) {
				AsyncOperation op = queue.get(0); 
				try {
					if (opIsRunnable(op, queue))
						return op;
					else 
						return null;
				} catch (ServiceException ex) {
					// an exception thrown from opIsRunnable(op, queue) should be thrown
					// in the context of "op" -- set the Exception and tell "op" to run
					op.setException(ex);
					return op;
				}
				// notreached! must return (only check top queue)
			}
		}
		
		return null;
	}

	public String dumpQueues() {
		mLock.lock();
		try {
			
			StringBuilder toRet = new StringBuilder();
			toRet.append("MaxLoad=").append(mMaxLoad).append(' ');
			toRet.append("TargetLoad=").append(mTargetLoad).append('\n');
			toRet.append("MaxOps =").append(mMaxSimultaneousOperations).append(' ');
			
			toRet.append("CurLoad=").append(mCurLoad).append(' ');
			assert(mLock.isHeldByCurrentThread());
			toRet.append("Running=").append(mRunningOperations).append('\n');

			for (Priority pri : Priority.values()) {
				List<AsyncOperation> queue = mOpQueue[pri.getLevel()];
				toRet.append('\t').append(pri).append(':');
				for (AsyncOperation op : queue) {
					toRet.append(op.toString()).append(' ');
				}
				toRet.append('\n');
			}
			
			return toRet.toString();
		} finally {
			mLock.unlock();
		}
	}
	
	//////////////
	// runtime parameters
	//
	int mMaxLoad;
	int mTargetLoad;
	int mMaxSimultaneousOperations;

	
	////////////// 
	// current state
	volatile int mCurLoad;
	volatile int mRunningOperations;
	
	
	/**
	 * If contention becomes a problem, this can be extended 
	 * to be one scheduler per CPU
	 */
	private static final Scheduler sScheduler[] = new Scheduler[1];
	
	static {
		sScheduler[0] = new Scheduler(10000, 10000, 1000);
	}
	
	static boolean SPEW = false;
	static final int MIN_LOAD = 1;
	ReentrantLock mLock = new ReentrantLock();
	ReentrantLock getLock() { return mLock; }
	List<AsyncOperation>[] mOpQueue = new ArrayList[Priority.NUM_PRIORITIES()];
	
	
	/************************************
	 * 
	 * 
	 * -------TEST CODE BELOW HERE------- 
	 * 
	 *
	 **************************************/

	
	
	static class TestOperation implements IOperation {
		protected String mOpType; 
		protected Priority mPriority;
		protected int mLoad;
		
		TestOperation(String optype, Priority pri, int load) {
			mOpType = optype;
			mPriority = pri;
			mLoad = load;
		}
				
		public int getLoad() { return mLoad; }
		public Priority getPriority() { return mPriority; }
		public String getName() { return mOpType; }
	}
	
	/**
	 *
	 */
	static class SearchTestThread extends TestOperation implements Runnable {
		Scheduler mSched;
		long mCount;
		String mName;
		
		SearchTestThread(Scheduler s, String name) {
			super("Search", Priority.INTERACTIVE_HIGH, 1);
			mSched = s;
			mName = name;
		}
		
		public String toString() { return "SearchTestThread"+mName+" "+super.toString(); }
		
		public void execute() {
			// System.out.println(mName+": Starting search");
			for (long i = 0; i < 1000000; i++)
				mCount++;
			
			try { Thread.sleep(30); } catch(InterruptedException e) {}

			for (long i = 0; i < 1000000; i++)
				mCount++;
			
			//System.out.println(mName+": Done with search");
//			System.out.println("SCHEDULER:\n"+mSched.toString());
			
		}
		
		public void run() {
			while(true) {
				try {
					mSched.schedule(this);
					try { 
						execute(); 
					} finally { 
						mSched.runCompleted(this); 
					}
				} catch (ServiceException e) {
					System.out.println("Caught except: "+e.toString());
					e.printStackTrace();
					return;
				}
				try { Thread.sleep(50); } catch(InterruptedException e) {}
			}
			
		}
	}
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		Scheduler s = new Scheduler(50, 30, 10);
		
		int NUMTHREADS = 30;
		
		SearchTestThread t[] = new SearchTestThread[NUMTHREADS];
		for (int i = 0; i < NUMTHREADS; i++) {
			t[i] = new SearchTestThread(s, Integer.toString(i));
			new Thread(t[i]).start();
		}
		
		while(true) {
			try {
				Thread.sleep(5000);
				System.out.println("SCHEDULER:\n"+s.dumpQueues());
			} catch (InterruptedException e) {}
		}
	}
}
