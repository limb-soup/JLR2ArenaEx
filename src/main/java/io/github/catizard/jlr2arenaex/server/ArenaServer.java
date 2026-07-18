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
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class ArenaServer extends WebSocketServer {
	private volatile boolean started = false;
	private volatile boolean autoRotateHost = false;
	private Consumer<Exception> exceptionHandler;
	public ServerState state = new ServerState();
	private SelectedBMSMessage[] selectedBMS = new SelectedBMSMessage[2];
	private byte[] userFile = new byte[1 << 27];
	private int userFileSize = 0;
	private String currentHash = null;
	private String currentTitle = null;
	private boolean strictHash = true;

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
		Message message = new Message(String.format("You have connected to the server.\n" +
													"Type \"/help\" or \"/?\" to view available commands.\n" +
													"Extensions enabled may be incompatible with LR2.\n" +
													"Hash check is %s.",
													strictHash ? "enabled" : "disabled"
													),
									  clientAddress,
									  true);
		send(ServerToClient.STC_MESSAGE, clientAddress, message.pack());
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
					currentHash = selectedBMSMessage.getMd5();
					currentTitle = selectedBMSMessage.getTitle();
					selectedBMS[1] = selectedBMS[0];
					selectedBMS[0] = selectedBMSMessage;
					userFileSize = 0;
					state.resetEveryone();
					broadcast(ServerToClient.STC_SELECTED_CHART_RANDOM, selectedBMSMessage.pack());
				}
				state.getPeer(clientAddress).ifPresent(peer -> {
					peer.setSelectedMD5(selectedBMSMessage.getMd5());
					if (!strictHash && selectedBMSMessage.getTitle().equals(currentTitle)){
						peer.setSelectedMD5(currentHash);
					}
					peer.setOption(selectedBMSMessage.getOption());
					peer.setGauge(selectedBMSMessage.getGauge());
					peer.setTotalNotes(selectedBMSMessage.getTotalNotes());
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
				String s = new String(data);
				if (s.length() > 1 && s.charAt(0) == '/'){
					if (s.equals("/help") || s.equals("/?")){
						Message message;
						message = new Message (String.format("/bms_send : send directory of the current chart for download\n" +
											 "/bms_dl : download uploaded chart\n" +
											 "/bms_request (1) : receive hash for (previously) selected chart\n" +
											 "/hash_enforce : toggle whether the bms file hash is enforced"),
									   clientAddress,
									   true
								   );
					send(ServerToClient.STC_MESSAGE, clientAddress, message.pack());
					return;
					}
					if (s.equals("/bms_send") && clientAddress.equals(state.getHost())){
						byte[] dat = new byte[4];
						dat[0] = 0;
						dat[1] = 0;
						dat[2] = 0;
						dat[3] = 0x10;
						send(ServerToClient.STC_ITEM,clientAddress,dat);
						return;
					}
					if (s.equals("/bms_dl") && userFileSize > 0){
						byte[] dat = new byte[4];
						dat[0] = (byte) userFileSize;
						dat[1] = (byte) (userFileSize >>> 8);
						dat[2] = (byte) (userFileSize >>> 16);
						dat[3] = (byte) (userFileSize >>> 24);
						dat[3] |= 0x0;
						send(ServerToClient.STC_ITEM,clientAddress,dat);
						System.out.println("Sending file size to user");
						return;
					}
					if (s.length() >= "/bms_request".length() &&
						s.substring(0,"/bms_request".length()).equals("/bms_request") &&
						selectedBMS[0] != null){
						if (s.substring("/bms_request".length()).contains("1") && selectedBMS[1] != null)
							send(ServerToClient.STC_SELECTED_CHART_RANDOM, clientAddress, selectedBMS[1].pack());
						else send(ServerToClient.STC_SELECTED_CHART_RANDOM, clientAddress, selectedBMS[0].pack());
					}
					if (s.equals("/hash_enforce")){
						strictHash = !strictHash;
						Message message = new Message(String.format("Hash check is %s.",
																	strictHash ? "enabled" : "disabled"
																	),
													  clientAddress,
													  true);
						broadcast(ServerToClient.STC_MESSAGE, message.pack());
					}
				}
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
			case CTS_FILE_TRANSFER -> {
				if (data.length < 4) {
					System.out.println("length < 4");
					return;
				}
				switch(data[3] >>> 4){
				case 0x0: { // client is sending userfile{
					if (!clientAddress.equals(state.getHost())) {
						System.out.println("Non-host attempted to send file");
						return;
					}
					userFileSize = 0;
					userFileSize += data[0] & 0xFF;
					userFileSize += (int)(data[1] & 0xFF) << 8;
					userFileSize += (int)(data[2] & 0xFF) << 16;
					userFileSize += (int)(data[3] & 0x0F) << 24;
					Message message = new Message(
												  "Upload failed!",
												  null,
												  true
												  );
					if (userFileSize >= userFile.length) {
						send(ServerToClient.STC_MESSAGE, clientAddress, message.pack());
						return;
					}
					if (data.length != userFileSize + 4) {
						Logger.getGlobal().info(String.format("[!] File size does not match size provided by user, %s",
															  state.getPeers().get(clientAddress).getUserName()));
						userFileSize = 0;
						send(ServerToClient.STC_MESSAGE, clientAddress, message.pack());
						return;
					}
					System.arraycopy(data, 4, userFile, 0, userFileSize);
					message = new Message(
										  String.format("%s uploaded the current chart; use \"/bms_dl\" to download.", state.getPeers().get(clientAddress).getUserName()),
										  clientAddress,
										  true
										  );
					broadcast(ServerToClient.STC_MESSAGE, message.pack());
					System.out.println("upload success");
					return;
				}
				case 0x1:{ // client requesting next segment of userfile
					int fileIndex = 0;
					fileIndex += data[0] & 0xFF;
					fileIndex += (int)(data[1] & 0xFF) << 8;
					fileIndex += (int)(data[2] & 0xFF) << 16;
					fileIndex += (int)(data[3] & 0x0F) << 24;
					if (fileIndex >= userFileSize){
						Logger.getGlobal().info(String.format("[!] File index provided by user, %s, is past the end of the file",
															  state.getPeers().get(clientAddress).getUserName()));
						return;
					}
					int remainder = userFileSize - fileIndex;
					if (remainder > 4000000){
						remainder = 4000000;
					}
					byte[] dat = new byte[5+remainder];
					dat[0] = (byte) ServerToClient.STC_ITEM.getValue();
					dat[1] = (byte) remainder;
					dat[2] = (byte) (remainder >>> 8);
					dat[3] = (byte) (remainder >>> 16);
					dat[4] = (byte) (remainder >>> 24);
					dat[4] |= (remainder == userFileSize - fileIndex) ? 0x30 : 0x20;
					System.arraycopy(userFile, fileIndex, dat, 5, remainder);
					this.getConnections().stream()
						.filter(conn -> clientAddress.equals(new Address(conn.getRemoteSocketAddress())))
						.findAny()
						.ifPresent(conn -> conn.send(dat));
					System.out.println("Sent file to client: " + remainder + " bytes");
					return;
				}
				default:{
					System.out.println("Received invalid byte, got: " + (data[3] >>> 4));
				}
				}
			}
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
