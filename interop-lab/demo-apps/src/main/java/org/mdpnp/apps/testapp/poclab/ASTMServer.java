package org.mdpnp.apps.testapp.poclab;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class ASTMServer {
	
	private int port;
	
	public static ByteBuffer bbACK=ByteBuffer.wrap(new byte[] {ASTMUtils.ACK});

	public ASTMServer() {
		// TODO Auto-generated constructor stub
	}
	
	public ASTMServer(int port) {
		this.port=port;
	}
	
	public void run() throws IOException {
		InetSocketAddress sockAddr=new InetSocketAddress(port);
		//Force IPv4 (for now)
		ServerSocketChannel serverChannel=ServerSocketChannel.open(StandardProtocolFamily.INET);
		System.err.println("Server socket using "+sockAddr.getHostString()+" and port "+sockAddr.getPort());
		serverChannel.bind(sockAddr);
		while(true) {
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
				System.err.println("Read "+bytesRead+" bytes");
				if(bytesRead<5) {
					if(bytes[0]==ASTMUtils.EOT);
					System.err.println("EOT received");
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
					System.err.println("Received line - "+line);
					
					System.err.println("Writing ACK");
					bbACK.rewind();
					acceptingChannel.write(bbACK);
					System.err.println("Wrote ACK");
					bytesRead=0;
					System.err.println("Read "+(linesRead++)+" lines so far");
					receivingBuffer.rewind();
				} else {
					System.err.println("Incomplete record...");
					String blah=new String(bytes,0,bytesRead);
					System.err.println(blah);
				}
			}
		}
	}

	public static void main(String[] args) {
		try {
			ASTMServer server=new ASTMServer(1182);
			server.run();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
