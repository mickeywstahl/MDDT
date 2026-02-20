package org.mdpnp.apps.testapp.poclab;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;

public class ASTMServer {
	
	/**
	 * Interface to be implemented by clients that wish to be notified that the server
	 * has received data.
	 */
	public interface DataCallback {
		/**
		 * Notifies the caller that data has been received by the server.
		 * @param bytes the bytes that have been received
		 */
		void dataReceived(byte[] bytes);
	}
	
	private ArrayList<DataCallback> callbacks=new ArrayList<>();
	
	private int port;
	
	public static ByteBuffer bbACK=ByteBuffer.wrap(new byte[] {ASTMUtils.ACK});
	
	private Thread serverThread;
	
	private boolean keepRunning=true;

	public ASTMServer() {
		// TODO Auto-generated constructor stub
	}
	
	public ASTMServer(int port) {
		this.port=port;
	}
	
	public void startServer() throws IOException {
		serverThread=new Thread() {
			public void run() {
				try {
					InetSocketAddress sockAddr=new InetSocketAddress(port);
					//Force IPv4 (for now)
					ServerSocketChannel serverChannel=ServerSocketChannel.open(StandardProtocolFamily.INET);
					System.err.println("Server socket using "+sockAddr.getHostString()+" and port "+sockAddr.getPort());
					serverChannel.bind(sockAddr);
					while(keepRunning) {
						SocketChannel acceptingChannel=serverChannel.accept();
						/*
						 * There is no arbitrary limit on the size of fields in ASTM, but given that it
						 * is effectively a record (line oriented) protocol, 4k seeems like it should be
						 * enough.  We allocate the buffer and wrap it in a ByteBuffer to ensure that the
						 * ByteBuffer has a backing array...
						 */
						byte[] bytes=new byte[4096];
						ByteBuffer receivingBuffer=ByteBuffer.wrap(bytes);
						//Create an array of ByteBuffers, but with only one buffer in it.
						int bytesRead=0;
						int linesRead=0;
						//System.err.println("Start of read...");
						
						while( (bytesRead+=acceptingChannel.read(receivingBuffer)) !=-1) {
							System.err.println("ASTMSERVER Read "+bytesRead+" bytes");
							if(bytesRead<5) {
								if(bytes[0]==ASTMUtils.EOT);
								System.err.println("ASTMSERVER EOT received");
								break;
							}
							if(bytes[0]==ASTMUtils.STX && bytes[bytesRead-5]==ASTMUtils.ETX) {
								//Complete record
								int slashRPos=0;
								for(int i=0;i<bytesRead;i++) {
									if(bytes[i]=='\r') {
										slashRPos=i;
										break;
									}
								}
								for(DataCallback callback : callbacks) {
									callback.dataReceived(bytes);
								}
								//TODO: More validation of the \r position
								String line=new String(bytes,1,slashRPos-2);
								System.err.println("ASTMSERVER Received line - "+line);
								
								System.err.println("ASTMSERVER Writing ACK");
								bbACK.rewind();
								acceptingChannel.write(bbACK);
								System.err.println("ASTMSERVER Wrote ACK");
								bytesRead=0;
								System.err.println("ASTMSERVER Read "+(linesRead++)+" lines so far");
								receivingBuffer.rewind();
							} else {
								System.err.println("ASTMSERVER Incomplete record...");
								String blah=new String(bytes,0,bytesRead);
								System.err.println(blah);
							}
						}
					}
				} catch (ClosedByInterruptException closed) {
					//Don't need a dump of this
					System.err.println("Server closed by interrupt");
				} catch (IOException ioe) {
					ioe.printStackTrace();
				} 
			}
		};
		serverThread.start();
	}
	
	public void stopServer() {
		keepRunning=false;
		/*
		 * The server thread is using a ServerSocketChannel, and that implements
		 * InterruptibleChannel, and that automatically causes the socket to be
		 * closed with ClosedByIntterupException.  That exits our run() method
		 * for us.
		 */
		serverThread.interrupt();
	}
	
	public void addCallback(DataCallback callback) {
		callbacks.add(callback);
	}

	public static void main(String[] args) {
		try {
			ASTMServer server=new ASTMServer(1182);
			server.startServer();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
