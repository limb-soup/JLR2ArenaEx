package io.github.catizard.jlr2arenaex.server;

import io.github.catizard.jlr2arenaex.enums.ClientToServer;
import io.github.catizard.jlr2arenaex.enums.ServerToClient;
import io.github.catizard.jlr2arenaex.network.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class ArenaServer extends WebSocketServer {
	private volatile boolean started = false;
	private volatile boolean autoRotateHost = false;
	private Consumer<Exception> exceptionHandler;
	public ServerState state = new ServerState();

	public ArenaServer() {
		this(2222);
	}

	public ArenaServer(int port) {
		super(new InetSocketAddress(port));
	}

	public void setExceptionHandler(Consumer<Exception> exceptionHandler) {
		this.exceptionHandler = exceptionHandler;
	}

	@Override
	public void onOpen(WebSocket conn, ClientHandshake handshake) {
		Address clientAddress = new Address(conn.getRemoteSocketAddress());
		Logger.getGlobal().info(String.format("[+] Client (%s:%d) connected", clientAddress.getHost(), clientAddress.getPort()));
	}

	@Override
	public void onClose(WebSocket conn, int code, String reason, boolean remote) {
		Address clientAddress = new Address(conn.getRemoteSocketAddress());
		Logger.getGlobal().info(String.format("[+] Client(%s:%d) disconnected", clientAddress.getHost(), clientAddress.getPort()));
		if (started) {
			state.getPeers().remove(clientAddress);
			if (state.getHost().equals(clientAddress)) {
				Optional<Address> any = state.getPeers().keySet().stream().findAny();
				any.ifPresent(nextHost -> state.setHost(nextHost));
			}
			if (!state.getPeers().isEmpty()) {
				PeerList newPeerList = state.getPeerList();
				broadcast(ServerToClient.STC_USERLIST, newPeerList.pack(), clientAddress);
			}
		}
	}

	@Override
	public void onMessage(WebSocket conn, String message) {
		Logger.getGlobal().info("[!] Server received text message, this is unexpected behavior!");
	}

	@Override
	public void onMessage(WebSocket conn, ByteBuffer message) {
		Address clientAddress = new Address(conn.getRemoteSocketAddress());
		try {
			parsePacket(clientAddress, message);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onError(WebSocket conn, Exception e) {
		if (exceptionHandler != null) {
			exceptionHandler.accept(e);
		}
	}

	@Override
	public void onStart() {
		started = true;
		Logger.getGlobal().info("[+] Server started at " + this.getPort());
	}

	@Override
	public void stop() throws InterruptedException {
		super.stop();
		started = false;
		state = new ServerState();
		Logger.getGlobal().info("[+] Server stopped");
	}

	public void setAutoRotateHost(boolean autoRotateHost) {
		this.autoRotateHost = autoRotateHost;
	}

	private void parsePacket(Address clientAddress, ByteBuffer bytes) throws IOException {
		char id = (char) bytes.get();
		ClientToServer ev = ClientToServer.from(id);
		byte[] data = new byte[bytes.remaining()];
		bytes.get(data, 0, data.length);
		// NOTE: If data is not representing an object, data in value object is the first byte of data array
		// In that case, don't use value object, see CTS_USERNAME for example
		Value value = null;
		if (data.length > 0) {
			try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(data)) {
				value = unpacker.unpackValue();
			} catch (IOException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		}
		switch (ev) {
			case CTS_SELECTED_BMS -> {
				SelectedBMSMessage selectedBMSMessage = new SelectedBMSMessage(value);
				if (state.getHost().equals(clientAddress)) {
					state.setCurrentRandomSeed(selectedBMSMessage.getRandomSeed());
					// state.setItemModeEnabled(...);
					state.resetEveryone();
					broadcast(ServerToClient.STC_SELECTED_CHART_RANDOM, selectedBMSMessage.pack());
				}
				state.getPeer(clientAddress).ifPresent(peer -> {
					peer.setSelectedMD5(selectedBMSMessage.getMd5());
					peer.setOption(selectedBMSMessage.getOption());
					peer.setGauge(selectedBMSMessage.getGauge());
				});
			}
			case CTS_PLAYER_SCORE -> {
				Score score = new Score(value);
				state.getPeer(clientAddress).ifPresent(peer -> {
					peer.setScore(score);
					broadcast(ServerToClient.STC_PLAYERS_SCORE, new ScoreMessage(score, clientAddress).pack());
				});
			}
			case CTS_CHART_CANCELLED -> {
				state.getPeer(clientAddress).ifPresent(peer -> {
					peer.setReady(false);
					peer.setSelectedMD5("");
					broadcast(ServerToClient.STC_PLAYERS_READY_UPDATE, state.getPeerList().pack());
				});
			}
			case CTS_LOADING_COMPLETE -> {
				state.getPeer(clientAddress).ifPresent(peer -> peer.setReady(true));
				if (autoRotateHost && isEveryoneReady()) {
					rotateHost();
				}
				broadcast(ServerToClient.STC_PLAYERS_READY_UPDATE, state.getPeerList().pack());
			}
			case CTS_USERNAME -> {
				String userName = new String(data);
				if (state.getPeers().isEmpty()) {
					state.setHost(clientAddress);
				}
				state.getPeers().put(clientAddress, new Peer());
				state.getPeers().get(clientAddress).setUserName(userName);
				// Tell the client side about whom it is
				send(ServerToClient.STC_CLIENT_REMOTE_ID, clientAddress, clientAddress.pack());
				// TODO: Sync item setting here
				// Update everyone's peer list
				broadcast(ServerToClient.STC_USERLIST, state.getPeerList().pack());
			}
			case CTS_MESSAGE -> {
				String s = new String(bytes.array(), StandardCharsets.UTF_8).substring(1);
				Message message = new Message(s, clientAddress, false);
				broadcast(ServerToClient.STC_MESSAGE, message.pack(), clientAddress);
			}
			case CTS_MISSING_CHART -> {
				Message message = new Message(
						String.format("[!] %s is missing the selected chart!", state.getPeers().get(clientAddress).getUserName()),
						clientAddress,
						true
				);
				broadcast(ServerToClient.STC_MESSAGE, message.pack());
			}
			case CTS_SET_HOST -> {
				if (!clientAddress.equals(state.getHost())) {
					return;
				}
				Address nextHost = new Address(value);
				if (!state.getPeers().containsKey(nextHost)) {
					Logger.getGlobal().severe("[!] Failed to set next host because user doesn't exist");
					return;
				}
				state.setHost(nextHost);
				broadcast(ServerToClient.STC_USERLIST, state.getPeerList().pack());
			}
			case CTS_KICK_USER -> {
				if (!clientAddress.equals(state.getHost())) {
					return;
				}
				Address target = new Address(value);
				if (!state.getPeers().containsKey(target)) {
					Logger.getGlobal().severe("[!] Failed to kick user because user doesn't exist");
					return;
				}
				findConnection(target).ifPresent(WebSocket::close);
			}
			case CTS_ITEM -> { /* TODO */ }
			case CTS_ITEM_SETTINGS -> { /* TODO */ }
			default -> Logger.getGlobal().severe("[!] unknown message received " + value);
		}
	}

	/**
	 * Send a message to all connected ends
	 *
	 * @param id   event id
	 * @param data message payload
	 */
	private void broadcast(ServerToClient id, byte[] data) {
		super.broadcast(PackUtil.concat(((byte) id.getValue()), data));
	}

	/**
	 * Similar to broadcast but filter out one exception
	 *
	 * @param id        event id
	 * @param data      message payload
	 * @param exception the address that we don't want to send for
	 */
	private void broadcast(ServerToClient id, byte[] data, Address exception) {
		this.getConnections().forEach(conn -> {
			Address remote = new Address(conn.getRemoteSocketAddress());
			if (remote.equals(exception)) {
				return;
			}
			conn.send(PackUtil.concat(((byte) id.getValue()), data));
		});
	}

	/**
	 * Send one message to one end
	 *
	 * @param id     event id
	 * @param remote end
	 * @param data   message payload
	 */
	private void send(ServerToClient id, Address remote, byte[] data) {
		this.getConnections().stream()
				.filter(conn -> remote.equals(new Address(conn.getRemoteSocketAddress())))
				.findAny()
				.ifPresent(conn -> conn.send(PackUtil.concat(((byte) id.getValue()), data)));
	}

	/**
	 * Find websocket connection by address
	 */
	private Optional<WebSocket> findConnection(Address address) {
		return this.getConnections().stream()
				.filter(conn -> address.equals(new Address(conn.getRemoteSocketAddress())))
				.findAny();
	}

	private boolean isEveryoneReady() {
		return state.getPeers().entrySet().stream().allMatch(entry -> entry.getValue().isReady());
	}

	private void rotateHost() {
		Address current = state.getHost();
		boolean isNext = false;
		Address first = null;
		for (Map.Entry<Address, Peer> entry : state.getPeers().entrySet()) {
			if (first == null) {
				first = entry.getKey();
			}
			if (isNext) {
				state.setHost(entry.getKey());
				break;
			}
			if (entry.getKey().equals(current)) {
				isNext = true;
			}
		}
		state.setHost(first);
	}
}
