/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.client.FetchContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.client.async.ClientRequester;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * A low-level key fetch which can be sent immediately. @see SendableRequest
 */
public abstract class SendableGet extends BaseSendableGet {

	/** Is this an SSK? */
	public abstract boolean isSSK();
	
	/** Parent BaseClientGetter. Required for schedulers. */
	public final ClientRequester parent;
	
	/** Get a numbered key to fetch. */
	public abstract ClientKey getKey(Object token);
	
	public Key getNodeKey(Object token) {
		ClientKey key = getKey(token);
		if(key == null) return null;
		return key.getNodeKey();
	}
	
	/** Get the fetch context (settings) object. */
	public abstract FetchContext getContext();
	
	/** Called when/if the low-level request succeeds. */
	public abstract void onSuccess(ClientKeyBlock block, boolean fromStore, Object token, RequestScheduler sched);
	
	/** Called when/if the low-level request fails. */
	public abstract void onFailure(LowLevelGetException e, Object token, RequestScheduler sched);
	
	/** Should the request ignore the datastore? */
	public abstract boolean ignoreStore();

	/** If true, don't cache local requests */
	public abstract boolean dontCache();

	// Implementation

	public SendableGet(ClientRequester parent) {
		this.parent = parent;
	}
	
	/** Do the request, blocking. Called by RequestStarter. 
	 * @return True if a request was executed. False if caller should try to find another request, and remove
	 * this one from the queue. */
	public boolean send(NodeClientCore core, final RequestScheduler sched, final Object keyNum) {
		ClientKey key = getKey(keyNum);
		if(key == null) {
			Logger.error(this, "Key is null in send(): keyNum = "+keyNum+" for "+this);
			return false;
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Sending get for key "+keyNum+" : "+key);
		FetchContext ctx = getContext();
		long now = System.currentTimeMillis();
		if(getCooldownWakeupByKey(key.getNodeKey()) > now) {
			Logger.error(this, "Key is still on the cooldown queue in send() for "+this+" - key = "+key, new Exception("error"));
			return false;
		}
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(isCancelled()) {
			if(logMINOR) Logger.minor(this, "Cancelled: "+this);
			// callbacks must initially run at HIGH_PRIORITY so they are executed before we remove the key from the currently running list
			sched.callFailure(this, new LowLevelGetException(LowLevelGetException.CANCELLED), null, NativeThread.HIGH_PRIORITY, "onFailure(cancelled)");
			return false;
		}
		try {
			try {
				core.realGetKey(key, ctx.localRequestOnly, ctx.cacheLocalRequests, ctx.ignoreStore);
			} catch (final LowLevelGetException e) {
				sched.callFailure(this, e, keyNum, NativeThread.HIGH_PRIORITY, "onFailure");
				return true;
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
				sched.callFailure(this, new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR), keyNum, NativeThread.HIGH_PRIORITY, "onFailure(caught throwable)");
				return true;
			}
			// Don't call onSuccess(), it will be called for us by backdoor coalescing.
			sched.succeeded(this);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			sched.callFailure(this, new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR), keyNum, NativeThread.HIGH_PRIORITY, "onFailure(caught throwable)");
			return true;
		}
		return true;
	}

	public void schedule() {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Scheduling "+this);
		getScheduler().register(this);
	}
	
	public ClientRequestScheduler getScheduler() {
		if(isSSK())
			return parent.sskScheduler;
		else
			return parent.chkScheduler;
	}

	/**
	 * Callback for when a block is found. Will be called on the database executor thread.
	 * @param key
	 * @param block
	 * @param sched
	 */
	public abstract void onGotKey(Key key, KeyBlock block, RequestScheduler sched);
	
	/**
	 * Get the time at which the key specified by the given token will wake up from the 
	 * cooldown queue.
	 * @param token
	 * @return
	 */
	public abstract long getCooldownWakeup(Object token);
	
	public abstract long getCooldownWakeupByKey(Key key);
	
	/** Reset the cooldown times when the request is reregistered. */
	public abstract void resetCooldownTimes();

	public final void unregister(boolean staySubscribed) {
		if(!staySubscribed)
			getScheduler().removePendingKeys(this, false);
		super.unregister(staySubscribed);
	}
	
	public final void unregisterKey(Key key) {
		getScheduler().removePendingKey(this, false, key);
	}

	public void internalError(final Object keyNum, final Throwable t, final RequestScheduler sched) {
		sched.callFailure(this, new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR, t.getMessage(), t), keyNum, NativeThread.MAX_PRIORITY, "Internal error");
	}

	/**
	 * Requeue a key after it has been on the cooldown queue for a while.
	 * Only requeue if our requeue time is less than or equal to the given time.
	 * @param key
	 */
	public abstract void requeueAfterCooldown(Key key, long time);

}
