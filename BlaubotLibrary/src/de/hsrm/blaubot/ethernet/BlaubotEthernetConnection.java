package de.hsrm.blaubot.ethernet;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;

import de.hsrm.blaubot.core.AbstractBlaubotConnection;
import de.hsrm.blaubot.core.IBlaubotDevice;
import de.hsrm.blaubot.util.Log;

/**
 * Connection implementation for Ethernet.
 * 
 * @author Henning Gross <mail.to@henning-gross.de>
 *
 */
public class BlaubotEthernetConnection extends AbstractBlaubotConnection {
	private static final String LOG_TAG = "BlaubotEthernetConnection";
	private Socket socket;
	private BlaubotEthernetDevice device;
	private DataInputStream dataInputStream;

	/**
	 * @param ethernetDevice the remote device abstraction
	 * @param clientSocket a connected socket to the remote device
	 */
	public BlaubotEthernetConnection(BlaubotEthernetDevice ethernetDevice, Socket clientSocket) {
		setUp(ethernetDevice, clientSocket);
	}

	private void setUp(BlaubotEthernetDevice ethernetDevice, Socket clientSocket) {
		this.socket = clientSocket;
		this.device = ethernetDevice;
		try {
			this.dataInputStream = new DataInputStream(clientSocket.getInputStream());
		} catch (IOException e) {
			throw new RuntimeException("Could not get InputStream from clientSocket. A socket handed to the constructor has to be connected!");
		}
	}
	
	private volatile boolean notifiedDisconnect = false;
	@Override
	protected synchronized void notifyDisconnected() {
		if(notifiedDisconnect)
			return;
		super.notifyDisconnected();
		notifiedDisconnect = true;
	}

	@Override
	public void disconnect() {
		if(Log.logDebugMessages()) {
			Log.d(LOG_TAG, "Disconnecting BlaubotEthernetConnection " + this + " ...");
		}
		try {
			socket.close();
		} catch (IOException e) {
			Log.e(LOG_TAG, "Failed to close socket", e);
		}
		this.notifyDisconnected();
	}

	@Override
	public boolean isConnected() {
		return socket.isConnected() && !socket.isClosed();
	}

	@Override
	public IBlaubotDevice getRemoteDevice() {
		return device;
	}

	private void handleSocketException(IOException e) throws SocketTimeoutException, IOException {
		if(Log.logWarningMessages()) {
			Log.w(LOG_TAG, "Got socket exception", e);
		}
		if(!(e instanceof SocketTimeoutException)) {
			this.disconnect();
		}
		throw e;
	}

	@Override
	public int read() throws SocketTimeoutException, IOException {
		try {
			return this.socket.getInputStream().read();
		} catch (IOException e) {
			this.handleSocketException(e);
			return -1; // will never get here
		}
	}

	@Override
	public int read(byte[] b) throws SocketTimeoutException, IOException {
		try {
			return this.socket.getInputStream().read(b);
		} catch (IOException e) {
			this.handleSocketException(e);
			return -1; // will never get here
		}
	}

	@Override
	public int read(byte[] buffer, int byteOffset, int byteCount) throws SocketTimeoutException, IOException {
		try {
			return this.socket.getInputStream().read(buffer, byteOffset, byteCount);
		} catch (IOException e) {
			this.handleSocketException(e);
			return -1; // will never get here
		}		
	}

	@Override
	public void write(int b) throws SocketTimeoutException, IOException {
		try {
			this.socket.getOutputStream().write(b);
		} catch (IOException e) {
			this.handleSocketException(e);
		}
	}

	@Override
	public void write(byte[] bytes) throws SocketTimeoutException, IOException {
		try {
			this.socket.getOutputStream().write(bytes);
		} catch (IOException e) {
			this.handleSocketException(e);
		}
	}

	@Override
	public void write(byte[] b, int off, int len) throws SocketTimeoutException, IOException {
		try {
			this.socket.getOutputStream().write(b,off,len);
		} catch (IOException e) {
			this.handleSocketException(e);
		}
	}

	@Override
	public void readFully(byte[] buffer) throws SocketTimeoutException, IOException {
		try {
			dataInputStream.readFully(buffer);
		} catch (IOException e) {
			handleSocketException(e);
		}
	}
	
	@Override
	public void readFully(byte[] buffer, int offset, int byteCount) throws SocketTimeoutException, IOException {
		try {
			dataInputStream.readFully(buffer, offset, byteCount);
		} catch (IOException e) {
			handleSocketException(e);
		}
	}

	@Override
	public String toString() {
		return "BlaubotEthernetConnection [socket=" + socket + ", device=" + device + "]";
	}

}
