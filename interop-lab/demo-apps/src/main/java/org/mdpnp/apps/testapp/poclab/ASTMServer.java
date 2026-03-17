package org.mdpnp.apps.testapp.poclab;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

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
		/**
		 * Notifies the caller that the server has been stopped.  Note well that if
		 * the server has been started from the main() method and it gets interrupted
		 * by Ctrl-C or other SIGINT type thing, there's no guarantee that this happens.
		 * This requires that the server is signalled to stop from something that isn't
		 * part of the JVM itself being shutdown.
		 */
		default void serverStopped() {

		};
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
				} finally {
					for(DataCallback callback : callbacks) {
						System.err.println("Calling serverStopped for callback");
						callback.serverStopped();
					}
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
		DataCallback fileCallback=null;
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
			fileCallback=new DataCallback() {

				SimpleDateFormat sdf=new SimpleDateFormat("YMMdd_hhmmss");
				File outputFile=new File("astm_data_"+sdf.format(new Date()));
				BufferedOutputStream bos=new BufferedOutputStream(new FileOutputStream(outputFile));

				@Override
				public void dataReceived(String line) {
					System.out.println("Test server received data "+line);
					try {
						bos.write(line.getBytes());
						bos.write('\n');
						//System.err.println("Server callback wrote line to bos");
						bos.flush();
					} catch (IOException ioe) {
						System.err.println("Failed to write line to file");
					}
				}

				@Override
				public void serverStopped() {
					try {
						Thread.dumpStack();
						bos.flush();
						bos.close();
						System.err.println("Server callback flushed and closed bos");
					} catch (IOException ioe) {
						System.err.println("Server callback failed to flush and close output file");
					}
				}
			};

			ASTMServer server=new ASTMServer(port);

			server.addCallback(fileCallback);
			server.startServer();
			/*
			 * Just for the purposes of the main method here, we join onto the server's serverThread,
			 * otherwise we'd exit from the main method, and the process would just be running the
			 * serverThread.  Exiting the main method would also cause the finally block to run, which
			 * would flush and close the output file in the hook.
			 *
			 * So this finally block is only really another best-endeavours attempt to get a clean flush
			 * and close on the output file in the hook from main.
			 */
			server.serverThread.join();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			System.err.println("Doing the finally from main...");
			if(fileCallback!=null) {
				fileCallback.serverStopped();
			}
		}
	}

}
