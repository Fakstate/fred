/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.db4o.ObjectContainer;

import freenet.client.FetchException;
import freenet.crypt.RandomSource;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.node.BaseSendableGet;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelGetException;
import freenet.node.LowLevelPutException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.PrioRunnable;
import freenet.node.RequestScheduler;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.node.SendableInsert;
import freenet.node.SendableRequest;
import freenet.node.SendableRequestItemKey;
import freenet.support.Fields;
import freenet.support.IdentityHashSet;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * Every X seconds, the RequestSender calls the ClientRequestScheduler to
 * ask for a request to start. A request is then started, in its own 
 * thread. It is removed at that point.
 */
public class ClientRequestScheduler implements RequestScheduler {
	
	private ClientRequestSchedulerCore schedCore;
	final ClientRequestSchedulerNonPersistent schedTransient;
	final transient ClientRequestSelector selector;
	
	private static volatile boolean logMINOR;
        private static volatile boolean logDEBUG;
	
	static {
		Logger.registerClass(ClientRequestScheduler.class);
	}
	
	/** Offered keys list. Only one, not split by priority, to prevent various attacks relating
	 * to offering specific keys and timing how long it takes for the node to request the key. 
	 * Non-persistent. */
	private final OfferedKeysList offeredKeys;
	// we have one for inserts and one for requests
	final boolean isInsertScheduler;
	final boolean isSSKScheduler;
	final boolean isRTScheduler;
	final RandomSource random;
	private final RequestStarter starter;
	private final Node node;
	public final String name;
	final DatastoreChecker datastoreChecker;
	public final ClientContext clientContext;
	final PersistentJobRunner jobRunner;
	
	public static final String PRIORITY_NONE = "NONE";
	public static final String PRIORITY_SOFT = "SOFT";
	public static final String PRIORITY_HARD = "HARD";
	private String choosenPriorityScheduler; 
	
	public ClientRequestScheduler(boolean forInserts, boolean forSSKs, boolean forRT, RandomSource random, RequestStarter starter, Node node, NodeClientCore core, String name, ClientContext context) {
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		this.isRTScheduler = forRT;
		schedTransient = new ClientRequestSchedulerNonPersistent(this, forInserts, forSSKs, forRT, random);
		this.datastoreChecker = core.storeChecker;
		this.starter = starter;
		this.random = random;
		this.node = node;
		this.clientContext = context;
		selector = new ClientRequestSelector(forInserts, forSSKs, forRT, this);
		
		this.name = name;
		
		this.choosenPriorityScheduler = PRIORITY_HARD; // Will be reset later.
		if(!forInserts) {
			offeredKeys = new OfferedKeysList(core, random, (short)0, forSSKs, forRT);
		} else {
			offeredKeys = null;
		}
		jobRunner = clientContext.jobRunner;
	}
	
	public void startCore(NodeClientCore core, long nodeDBHandle, ObjectContainer container) {
		schedCore = ClientRequestSchedulerCore.create(node, isInsertScheduler, isSSKScheduler, isRTScheduler, nodeDBHandle, container, COOLDOWN_PERIOD, this, clientContext);
	}
	
	/** Called by the  config. Callback
	 * 
	 * @param val
	 */
	public synchronized void setPriorityScheduler(String val){
		choosenPriorityScheduler = val;
	}
	
	static final int QUEUE_THRESHOLD = 100;
	
	public void registerInsert(final SendableRequest req, boolean persistent, ObjectContainer container) {
		if(!isInsertScheduler)
			throw new IllegalArgumentException("Adding a SendableInsert to a request scheduler!!");
		if(persistent) {
		    schedCore.innerRegister(req, container, clientContext, null);
		    starter.wakeUp();
		} else {
			schedTransient.innerRegister(req, null, clientContext, null);
			starter.wakeUp();
		}
	}
	
	/**
	 * Register a group of requests (not inserts): a GotKeyListener and/or one 
	 * or more SendableGet's.
	 * @param listener Listens for specific keys. Can be null if the listener
	 * is already registered i.e. on retrying.
	 * @param getters The actual requests to register to the request sender queue.
	 * @param persistent True if the request is persistent.
	 * @param onDatabaseThread True if we are running on the database thread.
	 * NOTE: delayedStoreCheck/probablyNotInStore is unnecessary because we only
	 * register the listener once.
	 * @throws FetchException 
	 */
	public void register(final HasKeyListener hasListener, final SendableGet[] getters, final boolean persistent, ObjectContainer container, final BlockSet blocks, final boolean noCheckStore) throws KeyListenerConstructionException {
		if(logMINOR)
			Logger.minor(this, "register("+persistent+","+hasListener+","+Fields.commaList(getters));
		if(isInsertScheduler) {
			IllegalStateException e = new IllegalStateException("finishRegister on an insert scheduler");
			throw e;
		}
		final KeyListener listener;
		if(hasListener != null) {
		    listener = hasListener.makeKeyListener(container, clientContext, false);
		    if(listener != null)
		        schedTransient.addPendingKeys(listener);
		    else
		        Logger.normal(this, "No KeyListener for "+hasListener);
		} else
		    listener = null;
		if(getters != null && !noCheckStore) {
		    for(SendableGet getter : getters)
		        datastoreChecker.queueTransientRequest(getter, blocks);
		} else {
		    boolean anyValid = false;
		    for(SendableGet getter : getters) {
		        if(!(getter.isCancelled(null) || getter.getCooldownTime(container, clientContext, System.currentTimeMillis()) != 0))
		            anyValid = true;
		    }
		    finishRegister(getters, false, container, anyValid, null);
		}
	}
	
	void finishRegister(final SendableGet[] getters, boolean persistent, ObjectContainer container, final boolean anyValid, final DatastoreCheckerItem reg) {
		if(logMINOR) Logger.minor(this, "finishRegister for "+Fields.commaList(getters)+" anyValid="+anyValid+" reg="+reg+" persistent="+persistent);
		if(isInsertScheduler) {
			IllegalStateException e = new IllegalStateException("finishRegister on an insert scheduler");
			for(SendableGet getter : getters) {
				if(persistent)
					container.activate(getter, 1);
				getter.internalError(e, this, container, clientContext, persistent);
				if(persistent)
					container.deactivate(getter, 1);
			}
			throw e;
		}
		if(persistent) {
			// Add to the persistent registration queue
				if(persistent)
					container.activate(getters, 1);
				if(logMINOR)
					Logger.minor(this, "finishRegister() for "+Fields.commaList(getters));
				if(anyValid) {
					boolean wereAnyValid = false;
					for(SendableGet getter : getters) {
						container.activate(getter, 1);
						// Just check isCancelled, we have already checked the cooldown.
						if(!(getter.isCancelled(container))) {
							wereAnyValid = true;
							if(!getter.preRegister(container, clientContext, true)) {
								schedCore.innerRegister(getter, container, clientContext, getters);
							}
						} else
							getter.preRegister(container, clientContext, false);

					}
					if(!wereAnyValid) {
						Logger.normal(this, "No requests valid");
					}
				} else {
					Logger.normal(this, "No valid requests passed in");
				}
				if(reg != null)
					container.delete(reg);
		} else {
			// Register immediately.
			for(SendableGet getter : getters) {
				
				if((!anyValid) || getter.isCancelled(null)) {
					getter.preRegister(container, clientContext, false);
					continue;
				} else {
					if(getter.preRegister(container, clientContext, true)) continue;
				}
				if(!getter.isCancelled(null))
					schedTransient.innerRegister(getter, null, clientContext, getters);
			}
			starter.wakeUp();
		}
	}

	/**
	 * Return a better non-persistent request, if one exists. If the best request
	 * is at the same priority as the priority passed in, 50% chance of accepting
	 * it.
	 * @param prio The priority of the persistent request we want to beat.
	 */
	public ChosenBlock getBetterNonPersistentRequest(short prio) {
		// removeFirstTransient() will return anything of the priority given or better.
		// We want to be fair on persistent vs transient, so we give it a 50% chance of wanting it to be *better* than the current priority, and a 50% chance of wanting it to be *at least as good as* the current priority.
		prio -= node.fastWeakRandom.nextBoolean() ? 1 : 0;
		if(prio < 0) return null;
		short fuzz = -1;
		if(PRIORITY_SOFT.equals(choosenPriorityScheduler))
			fuzz = -1;
		else if(PRIORITY_HARD.equals(choosenPriorityScheduler))
			fuzz = 0;
		return selector.removeFirstTransient(fuzz, random, offeredKeys, starter, schedTransient, prio, isRTScheduler, clientContext, null);
	}
	
	/**
	 * All the persistent SendableRequest's currently running (either actually in flight, just chosen,
	 * awaiting the callbacks being executed etc). We MUST compare by pointer, as this is accessed on
	 * threads other than the database thread, so we don't know whether they are active (and in fact 
	 * that may change under us!). So it can't be a HashSet.
	 */
	private final transient IdentityHashSet<SendableRequest> runningPersistentRequests = new IdentityHashSet<SendableRequest> ();
	
	@Override
	public void removeRunningRequest(SendableRequest request, ObjectContainer container) {
		synchronized(runningPersistentRequests) {
			if(runningPersistentRequests.remove(request)) {
				if(logMINOR)
					Logger.minor(this, "Removed running request "+request+" size now "+runningPersistentRequests.size());
			}
		}
		// We *DO* need to call clearCooldown here because it only becomes runnable for persistent requests after it has been removed from starterQueue.
		boolean active = container.ext().isActive(request);
		if(!active) container.activate(request, 1);
		request.clearCooldown(container, clientContext, false);
		if(!active) container.deactivate(request, 1);
	}
	
	@Override
	public boolean isRunningOrQueuedPersistentRequest(SendableRequest request) {
		synchronized(runningPersistentRequests) {
			if(runningPersistentRequests.contains(request)) return true;
		}
		return false;
	}
	
	/** The maximum number of requests that we will keep on the in-RAM request
	 * starter queue. */
	static final int MAX_STARTER_QUEUE_SIZE = 512; // two full segments
	
	/** The above doesn't include in-flight requests. In-flight requests will
	 * of course still have PersistentChosenRequest's 
	 * even though they are not on the starter queue and so don't count towards
	 * the above limit. So we have a higher limit before we complain that 
	 * something odd is happening.. (e.g. leaking PersistentChosenRequest's). */
	static final int WARNING_STARTER_QUEUE_SIZE = 800;
	private static final long WAIT_AFTER_NOTHING_TO_START = SECONDS.toMillis(60);
	
	/**
	 * Called by RequestStarter to find a request to run.
	 */
	@Override
	public ChosenBlock grabRequest() {
	    short fuzz = -1;
	    if(PRIORITY_SOFT.equals(choosenPriorityScheduler))
	        fuzz = -1;
	    else if(PRIORITY_HARD.equals(choosenPriorityScheduler))
	        fuzz = 0;
	    return selector.removeFirstTransient(fuzz, random, offeredKeys, starter, schedTransient, Short.MAX_VALUE, isRTScheduler, clientContext, null);
	}
	
	/**
	 * Remove a KeyListener from the list of KeyListeners.
	 * @param getter
	 * @param complain
	 */
	public void removePendingKeys(KeyListener getter, boolean complain) {
		boolean found = schedTransient.removePendingKeys(getter);
		if(schedCore != null)
			found |= schedCore.removePendingKeys(getter);
		if(complain && !found)
			Logger.error(this, "Listener not found when removing: "+getter);
	}

	/**
	 * Remove a KeyListener from the list of KeyListeners.
	 * @param getter
	 * @param complain
	 */
	public void removePendingKeys(HasKeyListener getter, boolean complain) {
		boolean found = schedTransient.removePendingKeys(getter);
		if(schedCore != null)
			found |= schedCore.removePendingKeys(getter);
		if(complain && !found)
			Logger.error(this, "Listener not found when removing: "+getter);
	}

	public void reregisterAll(final ClientRequester request, ObjectContainer container, short oldPrio) {
		schedTransient.reregisterAll(request, this, null, clientContext, oldPrio);
		if(schedCore != null)
			schedCore.reregisterAll(request, this, container, clientContext, oldPrio);
		starter.wakeUp();
	}
	
	public String getChoosenPriorityScheduler() {
		return choosenPriorityScheduler;
	}

	static final int TRIP_PENDING_PRIORITY = NativeThread.HIGH_PRIORITY-1;
	
	@Override
	public synchronized void succeeded(final BaseSendableGet succeeded, boolean persistent) {
		if(persistent) {
			try {
				jobRunner.queue(new PersistentJob() {

					@Override
					public boolean run(ClientContext context) {
						schedCore.succeeded(succeeded, null);
						return false;
					}
                                        @Override
					public String toString() {
						return "BaseSendableGet succeeded";
					}
					
				}, TRIP_PENDING_PRIORITY);
			} catch (PersistenceDisabledException e) {
				Logger.error(this, "succeeded() on a persistent request but database disabled", new Exception("error"));
			}
			// Boost the priority so the PersistentChosenRequest gets deleted reasonably quickly.
		} else
			schedTransient.succeeded(succeeded, null);
	}

	public void tripPendingKey(final KeyBlock block) {
		if(logMINOR) Logger.minor(this, "tripPendingKey("+block.getKey()+")");
		
		if(offeredKeys != null) {
			offeredKeys.remove(block.getKey());
		}
		final Key key = block.getKey();
		if(schedTransient.anyProbablyWantKey(key, clientContext)) {
			this.clientContext.mainExecutor.execute(new PrioRunnable() {

				@Override
				public void run() {
					schedTransient.tripPendingKey(key, block, null, clientContext);
				}

				@Override
				public int getPriority() {
					return TRIP_PENDING_PRIORITY;
				}
				
			}, "Trip pending key (transient)");
		}
		if(schedCore == null) return;
		if(schedCore.anyProbablyWantKey(key, clientContext)) {
			try {
				jobRunner.queue(new PersistentJob() {

					@Override
					public boolean run(ClientContext context) {
						if(logMINOR) Logger.minor(this, "tripPendingKey for "+key);
						schedCore.tripPendingKey(key, block, null, clientContext);
						return false;
					}
					
					@Override
					public String toString() {
						return "tripPendingKey";
					}
				}, TRIP_PENDING_PRIORITY);
			} catch (PersistenceDisabledException e) {
				// Nothing to do
			}
		}
	}
	
	/* FIXME SECURITY When/if introduce tunneling or similar mechanism for starting requests
	 * at a distance this will need to be reconsidered. See the comments on the caller in 
	 * RequestHandler (onAbort() handler). */
	@Override
	public boolean wantKey(Key key) {
		if(schedTransient.anyProbablyWantKey(key, clientContext)) return true;
		if(schedCore != null && schedCore.anyProbablyWantKey(key, clientContext)) return true;
		return false;
	}

	/** Queue the offered key */
	public void queueOfferedKey(final Key key, boolean realTime) {
		if(logMINOR)
			Logger.minor(this, "queueOfferedKey("+key);
		offeredKeys.queueKey(key);
		starter.wakeUp();
	}

	public void dequeueOfferedKey(Key key) {
		offeredKeys.remove(key);
	}

	@Override
	public long countQueuedRequests() {
	    return selector.countQueuedRequests(null, clientContext);
	}

	@Override
	public KeysFetchingLocally fetchingKeys() {
		return selector;
	}

	@Override
	public void removeFetchingKey(Key key) {
		// Don't need to call clearCooldown(), because selector will do it for each request blocked on the key.
		selector.removeFetchingKey(key);
	}

	@Override
	public void removeTransientInsertFetching(SendableInsert insert, SendableRequestItemKey token) {
		selector.removeTransientInsertFetching(insert, token);
		// Must remove here, because blocks selection and therefore creates cooldown cache entries.
		insert.clearCooldown(null, clientContext, false);
	}
	
	@Override
	public void callFailure(final SendableGet get, final LowLevelGetException e, int prio, boolean persistent) {
		if(!persistent) {
			get.onFailure(e, null, null, clientContext);
		} else {
			try {
				jobRunner.queue(new PersistentJob() {

					@Override
					public boolean run(ClientContext context) {
						get.onFailure(e, null, null, clientContext);
						return false;
					}
                                        @Override
					public String toString() {
						return "SendableGet onFailure";
					}
					
				}, prio);
			} catch (PersistenceDisabledException e1) {
				Logger.error(this, "callFailure() on a persistent request but database disabled", new Exception("error"));
			}
		}
	}
	
	@Override
	public void callFailure(final SendableInsert insert, final LowLevelPutException e, int prio, boolean persistent) {
		if(!persistent) {
			insert.onFailure(e, null, null, clientContext);
		} else {
			try {
				jobRunner.queue(new PersistentJob() {

					@Override
					public boolean run(ClientContext context) {
						insert.onFailure(e, null, null, context);
						return false;
					}
                                        @Override
					public String toString() {
						return "SendableInsert onFailure";
					}
					
				}, prio);
			} catch (PersistenceDisabledException e1) {
				Logger.error(this, "callFailure() on a persistent request but database disabled", new Exception("error"));
			}
		}
	}
	
	@Override
	public ClientContext getContext() {
		return clientContext;
	}

	/**
	 * @return True unless the key was already present.
	 */
	@Override
	public boolean addToFetching(Key key) {
		return selector.addToFetching(key);
	}
	
	@Override
	public boolean addTransientInsertFetching(SendableInsert insert, SendableRequestItemKey token) {
		return selector.addTransientInsertFetching(insert, token);
	}
	
	@Override
	public boolean hasFetchingKey(Key key, BaseSendableGet getterWaiting, boolean persistent, ObjectContainer container) {
		return selector.hasKey(key, null, false, null);
	}

	public long countPersistentWaitingKeys(ObjectContainer container) {
		if(schedCore == null) return 0;
		return schedCore.countWaitingKeys(container);
	}
	
	public boolean isInsertScheduler() {
		return isInsertScheduler;
	}

	public void removeFromAllRequestsByClientRequest(ClientRequester clientRequest, SendableRequest get, boolean dontComplain, ObjectContainer container) {
		if(get.persistent())
			schedCore.removeFromAllRequestsByClientRequest(get, clientRequest, dontComplain, container);
		else
			schedTransient.removeFromAllRequestsByClientRequest(get, clientRequest, dontComplain, null);
	}

	void addPersistentPendingKeys(KeyListener listener) {
		schedCore.addPendingKeys(listener);
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing ClientRequestScheduler in database", new Exception("error"));
		return false;
	}

	@Override
	public void wakeStarter() {
		starter.wakeUp();
	}

	public byte[] saltKey(boolean persistent, Key key) {
		return persistent ? schedCore.saltKey(key) : schedTransient.saltKey(key);
	}

	/** Only used in rare special cases e.g. ClientRequestSelector.
	 * FIXME add some interfaces to get rid of this gross layer violation. */
	Node getNode() {
		return node;
	}
	
}
