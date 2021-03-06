/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License. 
 *  
 */
package org.apache.directory.server.dhcp.service.store;

import com.google.common.base.Objects;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.anarres.dhcp.common.address.InterfaceAddress;
import org.anarres.dhcp.common.address.NetworkAddress;
import org.apache.directory.server.dhcp.DhcpException;
import org.apache.directory.server.dhcp.io.DhcpRequestContext;
import org.apache.directory.server.dhcp.messages.DhcpMessage;
import org.apache.directory.server.dhcp.messages.HardwareAddress;
import org.apache.directory.server.dhcp.messages.MessageType;
import org.apache.directory.server.dhcp.options.vendor.SubnetMask;
import org.apache.directory.server.dhcp.service.manager.AbstractLeaseManager;

/**
 * Very simple dummy/proof-of-concept implementation of a DhcpStore.
 *
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 */
public class SimpleStoreLeaseManager extends AbstractLeaseManager {
    // private static final String DEFAULT_INITIAL_CONTEXT_FACTORY =
    // "org.apache.directory.server.core.jndi.CoreContextFactory";

    // a map of current leases
    private final List<DhcpConfigSubnet> subnets = new ArrayList<DhcpConfigSubnet>();
    private final Cache<HardwareAddress, Lease> leases = CacheBuilder.newBuilder()
            .expireAfterAccess(TTL_LEASE.maxLeaseTime * 2, TimeUnit.SECONDS)
            .recordStats()
            .build();

    //This will suppress PMD.AvoidUsingHardCodedIP warnings in this class
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    public SimpleStoreLeaseManager() {
        subnets.add(new DhcpConfigSubnet(
                NetworkAddress.forString("192.168.168.0/24"),
                InetAddresses.forString("192.168.168.159"), InetAddresses.forString("192.168.168.179")));
    }

    /**
     * Finds the subnet for the given client address.
     */
    @CheckForNull
    protected DhcpConfigSubnet findSubnet(@Nonnull DhcpRequestContext context) {
        for (InterfaceAddress localAddress : context.getInterfaceAddresses()) {
            for (DhcpConfigSubnet subnet : subnets) {
                if (subnet.contains(localAddress.getAddress()))
                    return subnet;
            }
        }
        return null;
    }

    @Override
    public DhcpMessage leaseOffer(
            DhcpRequestContext context,
            DhcpMessage request,
            InetAddress clientRequestedAddress, long clientRequestedExpirySecs)
            throws DhcpException {
        HardwareAddress hardwareAddress = request.getHardwareAddress();
        Lease lease = leases.getIfPresent(hardwareAddress);
        if (lease != null) {
            lease.setState(Lease.LeaseState.OFFERED);
            return newReply(request, MessageType.DHCPOFFER, lease);
        }

        DhcpConfigSubnet subnet = findSubnet(context);
        if (subnet == null)
            return null;

        long leaseTimeSecs = getLeaseTime(TTL_OFFER, clientRequestedExpirySecs);

        // TODO: Allocate a new address.
        lease = new Lease(hardwareAddress, clientRequestedAddress);
        lease.setState(Lease.LeaseState.OFFERED);
        lease.setExpires(System.currentTimeMillis() / 1000 + leaseTimeSecs);
        lease.getOptions().setAddressOption(SubnetMask.class, subnet.getNetwork().getNetmaskAddress());
        leases.put(hardwareAddress, lease);

        return newReply(request, MessageType.DHCPOFFER, lease);
    }

    @Override
    public DhcpMessage leaseRequest(
            DhcpRequestContext context,
            DhcpMessage request,
            InetAddress clientRequestedAddress, long clientRequestedExpirySecs) throws DhcpException {
        HardwareAddress hardwareAddress = request.getHardwareAddress();
        Lease lease = leases.getIfPresent(hardwareAddress);
        if (lease == null)
            return null;
        if (!Objects.equal(lease.getClientAddress(), clientRequestedAddress))
            return null;

        lease.setState(Lease.LeaseState.ACTIVE);
        long leaseTimeSecs = getLeaseTime(TTL_LEASE, clientRequestedExpirySecs);
        lease.setExpires(System.currentTimeMillis() / 1000 + leaseTimeSecs);

        return newReply(request, MessageType.DHCPACK, lease);
    }

    @Override
    public boolean leaseDecline(
            DhcpRequestContext context,
            DhcpMessage request,
            InetAddress clientAddress) throws DhcpException {
        Lease lease = leases.asMap().remove(request.getHardwareAddress());
        return lease != null
                && Objects.equal(lease.getClientAddress(), clientAddress);
    }

    @Override
    public boolean leaseRelease(
            DhcpRequestContext context,
            DhcpMessage request,
            InetAddress clientAddress) throws DhcpException {
        Lease lease = leases.asMap().remove(request.getHardwareAddress());
        return lease != null
                && Objects.equal(lease.getClientAddress(), clientAddress);
    }
}
