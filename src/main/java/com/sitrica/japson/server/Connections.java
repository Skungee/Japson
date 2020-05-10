package com.sitrica.japson.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.gson.JsonObject;
import com.sitrica.japson.shared.Handler;

public class Connections extends Handler {

	private final List<JapsonConnection> connections = new ArrayList<>();
	private final Set<Listener> listeners = new HashSet<>(); 
	private final LoadingCache<InetSocketAddress, JapsonConnection> disconnected = CacheBuilder.newBuilder()
			.expireAfterWrite(10, TimeUnit.MINUTES)
			.maximumSize(1000)
			.removalListener(new RemovalListener<InetSocketAddress, JapsonConnection>() {
				@Override
				public void onRemoval(RemovalNotification<InetSocketAddress, JapsonConnection> notification) {
					JapsonConnection connection = notification.getValue();
					// Connection was reacquired.
					if (notification.getCause() == RemovalCause.EXPLICIT)
						return;
					listeners.forEach(listener -> listener.onForget(connection));
				}
			}).build(new CacheLoader<InetSocketAddress, JapsonConnection>() {
				@Override
				public JapsonConnection load(InetSocketAddress address) throws Exception {
					return getConnection(address.getAddress(), address.getPort())
							.orElseGet(() -> {
								JapsonConnection created = new JapsonConnection(address.getAddress(), address.getPort(), UUID.randomUUID() + "");
								connections.add(created);
								return created;
							});
				}
			});

	public Connections(JapsonServer japson) {
		super((byte) 0x00);
		listeners.addAll(japson.getListeners());
		Executors.newSingleThreadScheduledExecutor().schedule(() -> {
			for (JapsonConnection connection : connections) {
				if (System.currentTimeMillis() - connection.getLastUpdate() < japson.getTimeout())
					continue;
				listeners.forEach(listener -> listener.onUnresponsive(connection));
				connection.unresponsive();
				if (connection.getUnresponsiveCount() > japson.getMaxDisconnectAttempts()) {
					listeners.forEach(listener -> listener.onDisconnect(connection));
					disconnected.put(InetSocketAddress.createUnresolved(connection.getAddress().getHostName(), connection.getPort()), connection);
				}
			}
			connections.removeIf(connection -> connection.getUnresponsiveCount() > japson.getMaxDisconnectAttempts());
		}, japson.getHeartbeat(), TimeUnit.MILLISECONDS);
	}

	public JapsonConnection addConnection(InetAddress address, int port, String identification) {
		return getConnection(address, port)
				.orElseGet(() -> {
					JapsonConnection connection = new JapsonConnection(address, port, identification);
					listeners.forEach(listener -> listener.onAcquiredCommunication(connection));
					connections.add(connection);
					return connection;
				});
	}

	public Optional<JapsonConnection> getConnection(InetAddress address, int port) {
		Optional<JapsonConnection> optional = connections.stream()
				.filter(existing -> existing.getAddress().equals(address))
				.filter(existing -> existing.getPort() == port)
				.findFirst();
		if (optional.isPresent())
			return optional;
		InetSocketAddress socketAddress = InetSocketAddress.createUnresolved(address.getHostName(), port);
		optional = Optional.ofNullable(disconnected.getIfPresent(socketAddress));
		if (!optional.isPresent())
			return Optional.empty();
		JapsonConnection connection = optional.get();
		listeners.forEach(listener -> listener.onReacquiredCommunication(connection));
		connections.add(connection);
		disconnected.invalidate(socketAddress);
		return optional;
	}

	@Override
	public String handle(InetAddress address, int port, JsonObject json) {
		Optional.ofNullable(json.get("identification"))
				.ifPresent(identification -> {
					JapsonConnection connection = addConnection(address, port, identification.getAsString());
					connection.update();
				});
		return null;
	}

	public class JapsonConnection {

		private long updated = System.currentTimeMillis();
		private final String identification;
		private final InetAddress address;
		private final int port;
		private int fails = 0;

		public JapsonConnection(InetAddress address, int port, String identification) {
			this.identification = identification;
			this.address = address;
			this.port = port;
		}

		public String getIdentification() {
			return identification;
		}

		public int getUnresponsiveCount() {
			return fails;
		}

		public InetAddress getAddress() {
			return address;
		}

		public long getLastUpdate() {
			return updated;
		}

		public void unresponsive() {
			fails++;
		}

		public int getPort() {
			return port;
		}

		public void update() {
			updated = System.currentTimeMillis();
		}

	}

}