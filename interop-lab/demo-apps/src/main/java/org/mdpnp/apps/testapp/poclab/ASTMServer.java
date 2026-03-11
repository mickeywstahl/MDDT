package org.mdpnp.apps.testapp.poclab;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ASTMServer {
	
	private static final Logger log = LoggerFactory.getLogger(ASTMServer.class);

	/**
	 * Interface to be implemented by clients that wish to be notified that the server
	 * has received data.
	 */
	public interface DataCallback {
		/**
		 * Notifies the caller that data has been received by the server.
		 * @param line the String that has been received
		 */
		void dataReceived(String line);
	}
	
	private ArrayList<DataCallback> callbacks=new ArrayList<>();
	
	private int port;
	
	public static ByteBuffer bbACK=ByteBuffer.wrap(new byte[] {ASTMUtils.ACK});
	
	private Thread serverThread;
	
	private boolean keepRunning=true;

	/**
	 * Boolean to indicate if the server is running.  Initially false,
	 * as we aren't running...
	 */
	private boolean running=false;

	public ASTMServer() {
		// TODO Auto-generated constructor stub
	}
	
	public ASTMServer(int port) {
		this.port=port;
	}
	
	/**
	 * Indicates if the server is running.
	 *
	 * @return true if the server is already running.
	 */
	public boolean isRunning() {
		return running;
	}

	public void startServer() throws IOException {
		if(running) {
			//Already running...
			log.warn("ASTM Server already running");
			return;
			//TODO: We need some more checks here on whether the thread is extant, running and so on...
		}
		serverThread=new Thread() {
			public void run() {
				try {
					InetSocketAddress sockAddr=new InetSocketAddress(port);
					//Force IPv4 (for now)
					ServerSocketChannel serverChannel=ServerSocketChannel.open(StandardProtocolFamily.INET);
					log.info("Server socket using "+sockAddr.getHostString()+" and port "+sockAddr.getPort());
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
						running=true;
						
						while( (bytesRead+=acceptingChannel.read(receivingBuffer)) !=-1) {
							//System.err.println("ASTMSERVER Read "+bytesRead+" bytes");
							if(bytesRead<5) {
								if(bytes[0]==ASTMUtils.EOT);
								//System.err.println("ASTMSERVER EOT received");
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

								//TODO: More validation of the \r position
								String line=new String(bytes,1,slashRPos-2);
								for(DataCallback callback : callbacks) {
									callback.dataReceived(line);
								}
								//System.err.println("ASTMSERVER Received line - "+line);
								
								//System.err.println("ASTMSERVER Writing ACK");
								bbACK.rewind();
								acceptingChannel.write(bbACK);
								//System.err.println("ASTMSERVER Wrote ACK");
								bytesRead=0;
								//System.err.println("ASTMSERVER Read "+(linesRead++)+" lines so far");
								receivingBuffer.rewind();
							} else {
								log.warn("ASTMSERVER Incomplete record...");
								String blah=new String(bytes,0,bytesRead);
								log.warn(blah);
							}
						}
					}
				} catch (ClosedByInterruptException closed) {
					//Don't need a dump of this
					log.info("ASTM Server closed by interrupt");
					running=false;
				} catch (IOException ioe) {
					log.error("IO Exception in ASTM Server", ioe);
					running=false;
				} 
			}
		};
		serverThread.start();
		running=true;
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
			if(args.length!=1) {
				System.err.println("One argument required - a port number (e.g. 1182)");
				System.exit(1);
			}
			int port=-1;
			try {
				port=Integer.parseInt(args[0]);
			} catch (NumberFormatException nfe) {
				System.err.println("Port value must be numeric - not "+args[0]);
				System.exit(2);
			}
			ASTMServer server=new ASTMServer(port);
			DataCallback fileCallback=new DataCallback() {

				@Override
				public void dataReceived(String line) {
					// TODO Auto-generated method stub
				}
			};
			server.startServer();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
