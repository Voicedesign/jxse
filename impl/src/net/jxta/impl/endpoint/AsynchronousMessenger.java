package net.jxta.impl.endpoint;

import java.io.IOException;
import java.util.LinkedList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import net.jxta.endpoint.AbstractMessenger;
import net.jxta.endpoint.ChannelMessenger;
import net.jxta.endpoint.EndpointAddress;
import net.jxta.endpoint.Message;
import net.jxta.endpoint.Messenger;
import net.jxta.endpoint.MessengerState;
import net.jxta.peergroup.PeerGroupID;

/**
 * Base class for all truly asynchronous messenger implementations, that is, those which
 * are event-driven and do not require additional threads to operate. This base class
 * provides some common facilities, such as basic non-blocking queueing and messenger
 * state management.
 * 
 * @author Iain McGinniss (iain.mcginniss@onedrum.com)
 */
public abstract class AsynchronousMessenger extends AbstractMessenger {

    public static final String CLOSED_MESSAGE = "Messenger is closed. It cannot be used to send messages";

    private static final Logger LOG = Logger.getLogger(AsynchronousMessenger.class.getName());
    
    private final PeerGroupID homeGroupID;
    private final ArrayBlockingQueue<QueuedMessage> sendQueue;
    private final AtomicBoolean inputClosed = new AtomicBoolean(false);
    private final AtomicBoolean sending = new AtomicBoolean(false);
    
    private final MessengerState stateMachine = new MessengerState(true, distributingListener) {
        
        @Override
        protected void closeInputAction() {
            inputClosed.set(true);
        }

        @Override
        protected void closeOutputAction() {
            requestClose();
        }

        @Override
        protected void connectAction() {
            // immediately re-signal that the connection is down, to indicate
            // that we cannot reconnect
            downEvent();
        }

        @Override
        protected void failAllAction() {
            LinkedList<QueuedMessage> allMessages = new LinkedList<QueuedMessage>();
            sendQueue.drainTo(allMessages);
            for(QueuedMessage message : allMessages) {
                message.getWriteListener().writeFailure(new Exception("Messenger unexpectedly closed"));
            }
        }

        @Override
        protected void startAction() {
            // TODO: should we attempt to push messages here?
        }
    };
    
    public AsynchronousMessenger(PeerGroupID homeGroupID, EndpointAddress dest, int messageQueueSize) {
        super(dest);
        this.homeGroupID = homeGroupID;
        // TODO: assess whether this queue should be "fair" or not
        this.sendQueue = new ArrayBlockingQueue<QueuedMessage>(messageQueueSize);
    }
    
    public final void close() {
        closeEvent();
    }

    public final Messenger getChannelMessenger(PeerGroupID redirection, String service, String serviceParam) {
        return new ChannelMessenger(getDestinationAddress(), homeGroupID.equals(redirection) ? null : redirection, service, serviceParam) {

            public void close() {
                AsynchronousMessenger.this.close();
            }

            public EndpointAddress getLogicalDestinationAddress() {
                EndpointAddress rawLogical = AsynchronousMessenger.this.getLogicalDestinationAddress();

                if (rawLogical == null) {
                    return null;
                }
                return new EndpointAddress(rawLogical, origService, origServiceParam);
            }

            public int getState() {
                return AsynchronousMessenger.this.getState();
            }

            public void resolve() {
                AsynchronousMessenger.this.resolve();
            }

            public void sendMessageB(Message msg, String service, String serviceParam) throws IOException {
                String effectiveService = effectiveService(service);
                String effectiveParam = effectiveParam(service, serviceParam);
                AsynchronousMessenger.this.sendMessageB(msg, effectiveService, effectiveParam);
            }

            public boolean sendMessageN(Message msg, String service, String serviceParam) {
                String effectiveService = effectiveService(service);
                String effectiveParam = effectiveParam(service, serviceParam);
                return AsynchronousMessenger.this.sendMessageN(msg, effectiveService, effectiveParam);
            }
            
        };
    }

    public final int getState() {
        return stateMachine.getState();
    }

    public final void resolve() {
        // asynchronous messengers are assumed to be resolved from creation
    }

    public final void sendMessageB(Message msg, String service, String serviceParam) throws IOException {
        if(inputClosed.get()) {
            throw new IOException(CLOSED_MESSAGE);
        }
        
        TransportUtils.retargetMessage(msg, service, serviceParam, getLocalAddress(), dstAddress);
        AsynchronousMessageWriteListener listener = new AsynchronousMessageWriteListener(msg);
        
        try {
            // block on putting the element in the queue.
            sendQueue.put(new QueuedMessage(msg, listener));
            attemptPushMessages();
            listener.await();
            if(!listener.wasSuccessful()) {
                IOException failedWrite = new IOException("Failed to write message");
                failedWrite.initCause(listener.getFailureCause());
                throw failedWrite;
            }
        } catch(InterruptedException e) {
            IOException failure = new IOException("Interrupted while synchronously sending message");
            failure.initCause(e);
            throw failure;
        }
    }

    public final boolean sendMessageN(Message msg, String service, String serviceParam) {
        if(inputClosed.get()) {
            TransportUtils.markMessageWithSendFailure(msg, new IOException(CLOSED_MESSAGE));
            return false;
        }
        
        TransportUtils.retargetMessage(msg, service, serviceParam, getLocalAddress(), dstAddress);
        
        if(sendQueue.offer(new QueuedMessage(msg, new AsynchronousMessageWriteListener(msg)))) {
            msgsEvent();
            attemptPushMessages();
            return !TransportUtils.isMarkedWithFailure(msg);
        } else {
            saturatedEvent();
            TransportUtils.markMessageWithOverflowFailure(msg);
            return false;
        }
    }
    
    /**
     * Will attempt to pass any queued messages in {@link #sendQueue} to the subclass'
     * implementation of {@link #sendMessageImpl}. This is guaranteed to only be done
     * by one thread at a time, if subsequent threads try to send messages while another
     * is doing this the method will simply return without doing anything.
     */
    private void attemptPushMessages() {
        if(!sending.compareAndSet(false, true)) {
            // another thread is already sending
            return;
        }
        
        try {
            while(ableToSend() && pushSingleMessage());
        } finally {
            sending.set(false);
        }
    }

    private boolean ableToSend() {
        int sendableState = Messenger.CONNECTED
                          | Messenger.SENDING
                          | Messenger.SENDINGSATURATED
                          | Messenger.IDLE
                          | Messenger.SATURATED;
        return (getState() & sendableState) != 0;
    }
    
    private boolean pushSingleMessage() {
        if(!sending.get()) {
            // another thread is already sending
            return false;
        }
        
        // Strategy designed to avoid losing messages:
        // tenatively peek at the queue and attempt to send the message
        // if the real implementation accepts sending this message at this
        // time, then remove it from the queue.
        
        QueuedMessage message = sendQueue.peek();
        if(message != null) {
            if(sendMessageImpl(message)) {
                sendQueue.poll();
                msgsEvent();
                return true;
            }
            
            if(TransportUtils.isMarkedWithFailure(message.getMessage())) {
                sendQueue.poll();
                return false;
            }
        } else {
            idleEvent();
        }
        
        return false;
    }

    /**
     * It is intended that subclasses will invoke this method when space become available
     * on the write buffer for more messages. This will push any queued messages to the
     * child implementation via the {@link #sendMessageImpl(Message)} method, until it
     * indicates that the write buffer is full again.
     */
    protected final void pullMessages() {
        attemptPushMessages();
    }
    
    /**
     * It is intended that subclasses will invoke this method when the physical connection
     * is closed or fails for whatever reason.
     */
    protected final void connectionClosed() {
        downEvent();
    }
    
    private void closeEvent() {
        synchronized(stateMachine) {
            stateMachine.closeEvent();
        }
    }
    
    private void downEvent() {
        synchronized(stateMachine) {
            stateMachine.downEvent();
        }
    }
    
    private void idleEvent() {
        synchronized(stateMachine) {
            stateMachine.idleEvent();
        }
    }
    
    private void msgsEvent() {
        synchronized(stateMachine) {
            stateMachine.msgsEvent();
        }
    }
    
    private void saturatedEvent() {
        synchronized(stateMachine) {
            stateMachine.saturatedEvent();
        }
    }
    
    /*
     *  IMPLEMENTER RESPONSIBILITIES BELOW THIS POINT:
     */
    
    /**
     * Attempts to write the message to the underlying network endpoint, if there is buffer space
     * available. Implementations of this method MUST NOT BLOCK for any substantial amount of time.
     * @returns null if there is insufficient buffer space available to write the message.
     * @returns a MessengerWriteFuture instance if the message was successfully transferred to the
     * write buffer. This can later be checked for completion, when the message has been successfully
     * flushed to the network.
     */
    protected abstract boolean sendMessageImpl(QueuedMessage message);
    
    protected abstract EndpointAddress getLocalAddress();
    
    public abstract EndpointAddress getLogicalDestinationAddress();
    
    protected abstract void requestClose();
    
}
