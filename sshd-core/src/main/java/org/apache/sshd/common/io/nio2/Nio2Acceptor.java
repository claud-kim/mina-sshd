/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.common.io.nio2;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.PropertyResolverUtils;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.io.IoAcceptor;
import org.apache.sshd.common.io.IoHandler;

/**
 */
public class Nio2Acceptor extends Nio2Service implements IoAcceptor {
    public static final int DEFAULT_BACKLOG = 0;

    private final Map<SocketAddress, AsynchronousServerSocketChannel> channels;
    private int backlog = DEFAULT_BACKLOG;

    public Nio2Acceptor(FactoryManager manager, IoHandler handler, AsynchronousChannelGroup group) {
        super(manager, handler, group);
        channels = new ConcurrentHashMap<SocketAddress, AsynchronousServerSocketChannel>();
        backlog = PropertyResolverUtils.getIntProperty(manager, FactoryManager.SOCKET_BACKLOG, DEFAULT_BACKLOG);
    }

    @Override
    public void bind(Collection<? extends SocketAddress> addresses) throws IOException {
        for (SocketAddress address : addresses) {
            log.debug("Binding Nio2Acceptor to address {}", address);
            AsynchronousServerSocketChannel socket = AsynchronousServerSocketChannel.open(group);
            setOption(socket, FactoryManager.SOCKET_KEEPALIVE, StandardSocketOptions.SO_KEEPALIVE, null);
            setOption(socket, FactoryManager.SOCKET_LINGER, StandardSocketOptions.SO_LINGER, null);
            setOption(socket, FactoryManager.SOCKET_RCVBUF, StandardSocketOptions.SO_RCVBUF, null);
            setOption(socket, FactoryManager.SOCKET_REUSEADDR, StandardSocketOptions.SO_REUSEADDR, Boolean.TRUE);
            setOption(socket, FactoryManager.SOCKET_SNDBUF, StandardSocketOptions.SO_SNDBUF, null);
            setOption(socket, FactoryManager.TCP_NODELAY, StandardSocketOptions.TCP_NODELAY, null);
            socket.bind(address, backlog);
            SocketAddress local = socket.getLocalAddress();
            channels.put(local, socket);
            socket.accept(local, new AcceptCompletionHandler(socket));
        }
    }

    @Override
    public void bind(SocketAddress address) throws IOException {
        bind(Collections.singleton(address));
    }

    @Override
    public void unbind() {
        log.debug("Unbinding");
        unbind(getBoundAddresses());
    }

    @Override
    public void unbind(Collection<? extends SocketAddress> addresses) {
        for (SocketAddress address : addresses) {
            AsynchronousServerSocketChannel channel = channels.remove(address);
            if (channel != null) {
                try {
                    if (log.isTraceEnabled()) {
                        log.trace("unbind({})", address);
                    }
                    channel.close();
                } catch (IOException e) {
                    log.warn("unbind({}) {} while unbinding channel: {}",
                             address, e.getClass().getSimpleName(), e.getMessage());
                    if (log.isDebugEnabled()) {
                        log.debug("unbind(" + address + ") failure details", e);
                    }
                }
            } else {
                if (log.isTraceEnabled()) {
                    log.trace("No active channel to unbind {}", address);
                }
            }
        }
    }

    @Override
    public void unbind(SocketAddress address) {
        unbind(Collections.singleton(address));
    }

    @Override
    public Set<SocketAddress> getBoundAddresses() {
        return new HashSet<>(channels.keySet());
    }

    @Override
    public CloseFuture close(boolean immediately) {
        unbind();
        return super.close(immediately);
    }

    @Override
    public void doCloseImmediately() {
        for (SocketAddress address : channels.keySet()) {
            try {
                channels.get(address).close();
            } catch (IOException e) {
                log.debug("Exception caught while closing channel", e);
            }
        }
        super.doCloseImmediately();
    }

    class AcceptCompletionHandler extends Nio2CompletionHandler<AsynchronousSocketChannel, SocketAddress> {
        private final AsynchronousServerSocketChannel socket;

        AcceptCompletionHandler(AsynchronousServerSocketChannel socket) {
            this.socket = socket;
        }

        @SuppressWarnings("synthetic-access")
        @Override
        protected void onCompleted(AsynchronousSocketChannel result, SocketAddress address) {
            // Verify that the address has not been unbound
            if (!channels.containsKey(address)) {
                return;
            }

            Nio2Session session = null;
            try {
                // Create a session
                session = new Nio2Session(Nio2Acceptor.this, manager, handler, result);
                handler.sessionCreated(session);
                sessions.put(session.getId(), session);
                session.startReading();
            } catch (Throwable exc) {
                failed(exc, address);

                // fail fast the accepted connection
                if (session != null) {
                    try {
                        session.close();
                    } catch (Throwable t) {
                        log.warn("Failed (" + t.getClass().getSimpleName() + ")"
                                        + " to close accepted connection from " + address
                                        + ": " + t.getMessage(),
                                t);
                    }
                }
            }

            try {
                // Accept new connections
                socket.accept(address, this);
            } catch (Throwable exc) {
                failed(exc, address);
            }
        }

        @SuppressWarnings("synthetic-access")
        @Override
        protected void onFailed(final Throwable exc, final SocketAddress address) {
            if (channels.containsKey(address) && !disposing.get()) {
                log.warn("Caught " + exc.getClass().getSimpleName()
                                + " while accepting incoming connection from " + address
                                + ": " + exc.getMessage(),
                        exc);
            }
        }
    }
}
