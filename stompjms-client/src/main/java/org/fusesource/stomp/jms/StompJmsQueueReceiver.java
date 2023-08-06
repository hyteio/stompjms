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

import jakarta.jms.IllegalStateException;
import jakarta.jms.JMSException;
import jakarta.jms.Queue;
import jakarta.jms.QueueReceiver;

/**
 * Implementation of a Jms QueueReceiver
 */
public class StompJmsQueueReceiver extends StompJmsMessageConsumer implements QueueReceiver {

    /**
     * Constructor
     *
     * @param s
     */
    protected StompJmsQueueReceiver(AsciiBuffer id, StompJmsSession s, StompJmsDestination d, String selector) throws JMSException {
        super(id, s, d, selector);
    }

    /**
     * @return the Queue
     * @throws IllegalStateException
     * @see javax.jms.QueueReceiver#getQueue()
     */
    public Queue getQueue() throws IllegalStateException {
        checkClosed();
        return (Queue) this.destination;
    }
}
