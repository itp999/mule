/*
 * $Header$
 * $Revision$
 * $Date$
 * ------------------------------------------------------------------------------------------------------
 *
 * Copyright (c) Cubis Limited. All rights reserved.
 * http://www.cubis.co.uk
 *
 * The software in this package is published under the terms of the BSD
 * style license a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 */
package org.mule.routing.outbound;

import EDU.oswego.cs.dl.util.concurrent.ConcurrentHashMap;
import EDU.oswego.cs.dl.util.concurrent.CopyOnWriteArrayList;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mule.impl.MuleMessage;
import org.mule.impl.endpoint.MuleEndpoint;
import org.mule.impl.endpoint.MuleEndpointURI;
import org.mule.umo.UMOException;
import org.mule.umo.UMOMessage;
import org.mule.umo.UMOSession;
import org.mule.umo.endpoint.MalformedEndpointException;
import org.mule.umo.endpoint.UMOEndpoint;
import org.mule.umo.endpoint.UMOEndpointURI;
import org.mule.umo.provider.UniqueIdNotSupportedException;
import org.mule.umo.routing.CouldNotRouteOutboundMessageException;
import org.mule.umo.routing.RoutingException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * <code>AbstractRecipientList</code> is used to dispatch a single event to multiple recipients
 * over the same transport.  The recipient endpoints can be configured statically or
 * can be obtained from the message payload.
 *
 * @author <a href="mailto:ross.mason@cubis.co.uk">Ross Mason</a>
 * @version $Revision$
 */

public abstract class AbstractRecipientList extends FilteringOutboundRouter
{
    /**
     * logger used by this class
     */
    protected static transient Log logger = LogFactory.getLog(AbstractRecipientList.class);

    private Map recipientCache = new ConcurrentHashMap();

    public UMOMessage route(UMOMessage message, UMOSession session, boolean synchronous) throws RoutingException
    {
        List list = getRecipients(message);
        List results = new ArrayList();
        try
        {
            //synchronized(message) {
                message.setCorrelationId(message.getUniqueId());
                message.setCorrelationGroupSize(list.size());
            //}
            logger.debug("set correlationId to: " + message.getCorrelationId());
        } catch (UniqueIdNotSupportedException e)
        {
            throw new RoutingException("Cannot use recipientList router with transports that do not support a unique id", e, message);
        }

        UMOMessage result = null;
        //synchronized(list) {

        UMOEndpoint endpoint;
        for (Iterator iterator = list.iterator(); iterator.hasNext();)
        //for (int i =0; i < list.size(); i++)
        {
            String recipient = (String) iterator.next();
            endpoint = getRecipientEndpoint(message, recipient);

            try
            {
                if (synchronous)
                {
                    result = send(session, message, endpoint);
                    if(result!=null) {
                        results.add(result.getPayload());
                    } else {
                        logger.debug("No result was returned for sync call to: " + endpoint.getEndpointURI());
                    }
                } else
                {
                    dispatch(session, message, endpoint);
                }
            } catch (UMOException e)
            {
                throw new CouldNotRouteOutboundMessageException(e.getMessage(), e, message);
            }
        }
        
        if(results != null && results.size()==1) {
            return new MuleMessage(results.get(0), ((UMOMessage)result).getProperties());
        } else if(results.size()==0 ) {
            return null;
        }else {
            return new MuleMessage(results, (result==null ? null : result.getProperties()));
        }
        //}
    }

    protected UMOEndpoint getRecipientEndpoint(UMOMessage message, String recipient) throws RoutingException
    {
        UMOEndpointURI endpointUri = null;
        UMOEndpoint endpoint = (UMOEndpoint) recipientCache.get(recipient);
        if(endpoint!=null) return endpoint;
        try
        {
            endpointUri = new MuleEndpointURI(recipient);
            endpoint = MuleEndpoint.getOrCreateEndpointForUri(endpointUri, UMOEndpoint.ENDPOINT_TYPE_SENDER);
        } catch (MalformedEndpointException e)
        {
            throw new RoutingException("Could not route message as recipient endpointUri was malformed: " + recipient, e, message);
        } catch (UMOException e) {
            throw new RoutingException("Could not route message as could not get endpoint for endpointUri: " + recipient, e, message);
        }
        recipientCache.put(recipient, endpoint);
        return endpoint;
    }
    protected abstract CopyOnWriteArrayList getRecipients(UMOMessage message);

}
