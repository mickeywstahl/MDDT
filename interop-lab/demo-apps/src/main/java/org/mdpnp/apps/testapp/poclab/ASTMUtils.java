package org.mdpnp.apps.testapp.poclab;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;

public class ASTMUtils {
	
	/**
	 * Start of record indicator
	 */
	public static final byte STX=0x02;
	
	/**
	 * End of record indicator
	 */
	public static final byte ETX=0x03;
	
	/**
	 * End of transmission indicator.
	 */
	public static final byte EOT=0x04;
	
	/**
	 * Response request indicator 
	 */
	public static final byte RER=0x05;
	
	/**
	 * Acknowledgement byte from server
	 */
	public static final byte ACK=0x06;
	
	/**
	 * Something else
	 */
	public static final byte ETB=0x23;

	public ASTMUtils() {
		
	}
	
	/**
	 * Calculates a checksum for an input sequence that makes up a record (frame?)
	 * 
	 * It seems to be expected that the checksum is an uppercase string representation such as
	 * 
	 * 1F
	 * 
	 * rather than a byte sequence
	 * 
	 * @param inputBytes
	 * @return a 2 character string representation of the checksum
	 */
	public static String ASTMChecksum(byte[] inputBytes) {
		int sum=0;
		int i=0;
		boolean complete=false;
		while(i<inputBytes.length && ! complete) {
			byte b=inputBytes[i++];
			switch (b) {
			case STX:
				sum=0;	//Start of record, reset sum
				break;
			case ETX:
			case ETB:
				sum+=b;
				complete=true;
				break;
			default:
				sum+=b;
				break;
			}	//End of switch
		}
		
		sum=sum%256;
		String s=String.format("%x", sum);
		if(s.length()==1) {
			s="0"+s;
		}
		
		return s.toUpperCase();
	}
	
	/**
	 * Sends data to an ASTM server.
	 * 
	 * The protocol is "line" or message oriented.  The lines should be correctly formatted for ASTM, which broadly means<br>
	 * <br>
	 * &lt;STX&gt;&lt;Frame Data&gt;&lt;CR&gt;&lt;ETX&gt;&lt;2 CHARACTER CHECKSUM&gt;&lt;CR&gt;&lt;LF&gt;
	 * 
	 * @param host hostname of the remote server to connect to.  May be null or a zero length string, in which case
	 * localhost is used.
	 * @param port port number of the remote server to connect to.
	 * @param lines Array of "lines" to send, in byte array form
	 * @throws UnknownHostException if the given host name cannot be resolved
	 * @throws IOException if there is a problem reading or writing data, or if an ACK byte is not received from the server
	 * after transmitting a "line" 
	 */
	public static void transmit(String host, int port, ArrayList<byte[]> lines) throws UnknownHostException, IOException {
		if(host==null || host.length()==0) {
			host="localhost";
		}
		InetAddress addr=InetAddress.getByName(host);
		SocketAddress serverEnd=new InetSocketAddress(addr, port);
		SocketChannel socketChannel=SocketChannel.open(serverEnd);
		//We should only receive very small responses from the "server"
		ByteBuffer response=ByteBuffer.allocate(64);
		int linesSent=0;
		for(byte[] bytes : lines) {
			response.rewind();
			ByteBuffer bb=ByteBuffer.wrap(bytes);
			socketChannel.write(bb);
			socketChannel.read(response);
			byte b=response.get(0);
			if(b!=ACK) {
				throw new IOException("Did not get ACK from server after sending line "+linesSent);
			}
			System.err.println("Received ACK from server for line "+linesSent);
			linesSent++;
		}
	}

}
