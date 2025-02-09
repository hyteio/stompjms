/**
 * Copyright (C) 2010-2011, FuseSource Corp.  All rights reserved.
 *
 *     http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */

package org.fusesource.stomp.jms;

import org.fusesource.hawtbuf.AsciiBuffer;
import org.fusesource.hawtdispatch.CustomDispatchSource;
import org.fusesource.hawtdispatch.Dispatch;
import org.fusesource.hawtdispatch.OrderedEventAggregator;
import org.fusesource.hawtdispatch.Task;
import org.fusesource.stomp.client.Promise;
import org.fusesource.stomp.codec.StompFrame;
import org.fusesource.stomp.jms.message.StompJmsMessage;

import jakarta.jms.IllegalStateException;
import jakarta.jms.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * implementation of a Jms Message Consumer
 */
public class StompJmsMessageConsumer implements MessageConsumer, StompJmsMessageListener {
    
    final StompJmsSession session;
    final StompJmsDestination destination;
    final AsciiBuffer id;
    final AtomicBoolean closed = new AtomicBoolean();
    boolean started;
    MessageListener messageListener;
    final String messageSelector;
    final MessageQueue messageQueue;
    final Lock lock = new ReentrantLock();
    final AtomicBoolean suspendedConnection = new AtomicBoolean();

    protected StompJmsMessageConsumer(final AsciiBuffer id, StompJmsSession s, StompJmsDestination destination, String selector) throws JMSException {
        this.id = id;
        this.session = s;
        this.destination = destination;
        this.messageSelector = selector;

        if(  session.acknowledgementMode==Session.SESSION_TRANSACTED ) {
            this.messageQueue = new TxMessageQueue(session.consumerMessageBufferSize);
        } else {
            this.messageQueue = new MessageQueue(session.consumerMessageBufferSize);
        }
    }

    public boolean tcpFlowControl() {
        // Then the STOMP client does not need to issue acks to the server, we suspend
        // TCP reads to avoid memory overruns.
        return session.acknowledgementMode==StompJmsSession.SERVER_AUTO_ACKNOWLEDGE;
    }

    public void init() throws JMSException {
        session.add(this);
    }

    public boolean isDurableSubscription() {
        return false;
    }


    public boolean isBrowser() {
        return false;
    }

    /**
     * @throws JMSException
     * @see javax.jms.MessageConsumer#close()
     */
    public void close() throws JMSException {
        if(closed.compareAndSet(false, true)) {
            this.session.remove(this);
            if( suspendedConnection.compareAndSet(true, false) ) {
                session.channel.connection().resume();
            }
        }
    }


    public MessageListener getMessageListener() throws JMSException {
        checkClosed();
        return this.messageListener;
    }

    /**
     * @return the Message Selector
     * @throws JMSException
     * @see javax.jms.MessageConsumer#getMessageSelector()
     */
    public String getMessageSelector() throws JMSException {
        checkClosed();
        return this.messageSelector;
    }

    /**
     * @return a Message or null if closed during the operation
     * @throws JMSException
     * @see javax.jms.MessageConsumer#receive()
     */
    public Message receive() throws JMSException {
        checkClosed();
        try {
            return copy(ack(this.messageQueue.dequeue(-1)));
        } catch (Exception e) {
            throw StompJmsExceptionSupport.create(e);
        }
    }

    /**
     * @param timeout
     * @return a MEssage or null
     * @throws JMSException
     * @see javax.jms.MessageConsumer#receive(long)
     */
    public Message receive(long timeout) throws JMSException {
        checkClosed();
        try {
            return copy(ack(this.messageQueue.dequeue(timeout)));
        } catch (InterruptedException e) {
            throw StompJmsExceptionSupport.create(e);
        }
    }

    /**
     * @return a Message or null
     * @throws JMSException
     * @see javax.jms.MessageConsumer#receiveNoWait()
     */
    public Message receiveNoWait() throws JMSException {
        checkClosed();
        Message result = copy(ack(this.messageQueue.dequeueNoWait()));
        return result;
    }

    /**
     * @param listener
     * @throws JMSException
     * @see javax.jms.MessageConsumer#setMessageListener(javax.jms.MessageListener)
     */
    public void setMessageListener(MessageListener listener) throws JMSException {
        checkClosed();
        this.messageListener = listener;
        drainMessageQueueToListener();

    }


    protected void checkClosed() throws IllegalStateException {
        if (this.closed.get()) {
            throw new IllegalStateException("The MessageProducer is closed");
        }
    }

    StompJmsMessage copy(final StompJmsMessage message) throws JMSException {
        if( message == null ) {
            return null;
        }
        return message.copy();
    }

    StompJmsMessage ack(final StompJmsMessage message) {
        if( message!=null ) {
            if( message.getAcknowledgeCallback()!=null ) {
                // Message has been received by the app.. expand the credit window
                // so that we receive more messages.
                StompFrame frame = session.channel.serverAdaptor.createCreditFrame(this, message.getFrame());
                if( frame != null ) {
                    try {
                        session.channel.sendFrame(frame);
                    } catch (IOException ignore) {
                    }
                }
                // don't actually ack yet.. client code does it.
                return message;
            }
            doAck(message);
        }
        return message;
    }

    private void doAck(final StompJmsMessage message) {
        if( tcpFlowControl()) {
            // We may need to resume the message flow.
            if( !this.messageQueue.isFull() ) {
                if( suspendedConnection.compareAndSet(true, false) ) {
                    session.channel.connection().resume();
                }
            }
        } else {
            try {
                StompChannel channel = session.channel;
                if( channel == null ) {
                    throw new JMSException("Consumer closed");
                }

                final Promise<StompFrame> ack = new Promise<StompFrame>();
                switch( session.acknowledgementMode ) {
                    case Session.CLIENT_ACKNOWLEDGE:
                        channel.ackMessage(id,  message.getMessageID(), null, ack);
                        break;
                    case Session.AUTO_ACKNOWLEDGE:
                        channel.ackMessage(id,  message.getMessageID(), null, ack);
                        break;
                    case Session.DUPS_OK_ACKNOWLEDGE:
                        channel.ackMessage(id,  message.getMessageID(), null, null);
                        ack.onSuccess(null);
                        break;
                    case Session.SESSION_TRANSACTED:
                        channel.ackMessage(id,  message.getMessageID(), session.currentTransactionId, null);
                        ack.onSuccess(null);
                        break;
                    case StompJmsSession.SERVER_AUTO_ACKNOWLEDGE:
                        throw new IllegalStateException("This should never get called.");
                }
                ack.await();

            } catch (JMSException e) {
                session.connection.onException(e);
                throw new RuntimeException(e);
            } catch (Exception e) {
                session.connection.onException(new JMSException("Exception occurred sending ACK for message id : " + message.getMessageID()));
                throw new RuntimeException("Exception occurred sending ACK for message id : " + message.getMessageID(), e);
            }
        }
    }


    /**
     * @param message
     */
    public void onMessage(final StompJmsMessage message) {
        lock.lock();
        try {
            if( session.acknowledgementMode == Session.CLIENT_ACKNOWLEDGE ) {
                message.setAcknowledgeCallback(new Callable<Void>(){
                    public Void call() throws Exception {
                        if( session.channel == null ) {
                            throw new jakarta.jms.IllegalStateException("Session closed.");
                        }
                        doAck(message);
                        return null;
                    }
                });
            }
//            System.out.println(""+session.channel.getSocket().getLocalAddress() +" recv "+ message.getMessageID());
            this.messageQueue.enqueue(message);
            // We may need to suspend the message flow.
            if( tcpFlowControl() && this.messageQueue.isFull() ) {
                if(suspendedConnection.compareAndSet(false, true) ) {
                    session.channel.connection().suspend();
                }
            }
        } finally {
            lock.unlock();
        }
        if (this.messageListener != null && this.started) {
            session.getExecutor().execute(new Runnable() {
                public void run() {
                    StompJmsMessage message;
                    while( session.isStarted() && (message=messageQueue.dequeueNoWait()) !=null ) {
                        try {
                            messageListener.onMessage(copy(ack(message)));
                        } catch (Exception e) {
                            session.connection.onException(e);
                        }
                    }
                }
            });
        }
    }

    /**
     * @return the id
     */
    public AsciiBuffer getId() {
        return this.id;
    }

    /**
     * @return the Destination
     */
    public StompJmsDestination getDestination() {
        return this.destination;
    }

    public void start() {
        lock.lock();
        try {
            this.started = true;
            this.messageQueue.start();
            drainMessageQueueToListener();
        } finally {
            lock.unlock();
        }
    }

    public void stop() {
        lock.lock();
        try {
            this.started = false;
            this.messageQueue.stop();
        } finally {
            lock.unlock();
        }
    }

    void rollback() {
        ((TxMessageQueue)this.messageQueue).rollback();
    }

    void commit() {
        ((TxMessageQueue)this.messageQueue).commit();
    }

    void drainMessageQueueToListener() {
        MessageListener listener = this.messageListener;
        if (listener != null) {
            if (!this.messageQueue.isEmpty()) {
                List<StompJmsMessage> drain = this.messageQueue.removeAll();
                for (StompJmsMessage m : drain) {
                    final StompJmsMessage copy;
                    try {
                        listener.onMessage(copy(ack(m)));
                    } catch (Exception e) {
                        session.connection.onException(e);
                    }
                }
                drain.clear();
            }
        }
    }

    protected int getMessageQueueSize() {
        return this.messageQueue.size();
    }

    public boolean getNoLocal() throws IllegalStateException {
        return false;
    }
}
