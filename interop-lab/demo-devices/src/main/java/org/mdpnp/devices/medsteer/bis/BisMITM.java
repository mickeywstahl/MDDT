package org.mdpnp.devices.medsteer.bis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.mdpnp.devices.serial.AbstractSerialDevice;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.serial.SerialSocket.DataBits;
import org.mdpnp.devices.serial.SerialSocket.FlowControl;
import org.mdpnp.devices.serial.SerialSocket.Parity;
import org.mdpnp.devices.serial.SerialSocket.StopBits;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.rtiapi.data.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;

/**
 * A "man in the middle" interface for the BIS monitor<br/>
 * 
 * idx will be 0 for the port connected the device requesting the data (e.g. EasyTIVA).<br/>
 * idx will be 1 for the port connected to the actual BIS monitor that we are getting the data from.
 * 
 * So the flow of request and response data will be
 * 
 * fromRequestor -> toDevice -> fromDevice -> toRequestor
 *  
 * @author MDPNP
 *
 */
public class BisMITM extends AbstractSerialDevice {
	
	private static final Logger log = LoggerFactory.getLogger(BisMonitor.class);
	
	private BufferedReader fromDevice;
	private InputStream fromRequestor;
	private BufferedOutputStream toRequestor, toDevice, toLog;
	
	/**
	 * Deciding if we are connected or not is tricky.  We'll assume we are once we've got anything in either direction.
	 */
	private boolean connected;
	
	/**
	 * The receive buffer
	 */
	ByteBuffer m_rcv_buff=ByteBuffer.allocate(0x8000);

	public BisMITM(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
		this(subscriber, publisher, eventLoop,2);
		// TODO Auto-generated constructor stub
	}

	public BisMITM(Subscriber subscriber, Publisher publisher, EventLoop eventLoop, int countSerialPorts) {
		super(subscriber, publisher, eventLoop, countSerialPorts);
		AbstractSimulatedDevice.randomUDI(deviceIdentity);
        writeDeviceIdentity();
	}

	@Override
	protected void doInitCommands(int idx) throws IOException {
		connected=true;
		reportConnected("Fake connection report");
	}

	@Override
	protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
		System.err.println("process starts for idx "+idx);
		Thread.dumpStack();
		if(idx==0) {
			//fromRequestor=new BufferedInputStream(inputStream);
			fromRequestor=inputStream;
			toRequestor=new BufferedOutputStream(outputStream);
			toLog=new BufferedOutputStream(new FileOutputStream(System.getProperty("user.home")+"/bis_"+System.currentTimeMillis()+".log"));
			System.err.println("Created in and out for requestor "+idx);
		} else {
			fromDevice=new BufferedReader(new InputStreamReader(inputStream));
			toDevice=new BufferedOutputStream(outputStream);
			System.err.println("Created in and out for device "+idx);
		}
		byte oneByteCommand[]=new byte[1];
		while(true) {
			if(idx==0) {
				System.err.println("Receiving from requestor idx "+idx);
				//Commands from requestor will be single bytes;
				int read=fromRequestor.read(oneByteCommand);
				System.err.println("Read one byte command FROM REQUESTOR - "+(char)oneByteCommand[0]);
				if(read==1) {
					if(!connected) {
						reportConnected("Received a command from requestor");
					}
					//Forward the one byte command to the BIS.
					toDevice.write(oneByteCommand);
					toDevice.flush();
					//And handle any response.
					switch (oneByteCommand[0]) {
					case 'C':
						log.info("Not expecting response from BIS for command 'C'");
						keepGoing=false;	//Tell any existing data thread to stop.
						if(dataThread!=null) {
							dataThread.interrupt();
						}
						break;
					case 'V':
						if(dataThread==null) {
							log.info("Asking BIS for version info for command 'V'");
							byte[] versionBytes=recv();
							toRequestor.write(versionBytes);
							toRequestor.flush();
						} else {
							//This seems an unlikely situation that didn't happen once we got everything working.
							System.err.println("Skipping version request as we are already sending data");
						}
						break;
					case 'U':
						System.err.println("Starting data thread");
						log.info("Starting data thread for command 'U'");
						startDataThread();
						break;
					default:
						System.err.println("Unexpected command "+(byte)oneByteCommand[0]+" from EasyTIVA to BIS");
						log.error("Unexpected command "+(byte)oneByteCommand[0]+" from EasyTIVA to BIS");
						break;
					}
				}
			}
		}
	}
	
	/**
	 * This is used to receive data from the BIS.  It's a simplified version of the code that's used
	 * in the EasyTIVA driver, because in ASCII mode we just need to read lines, and Java provides
	 * nice Reader classes for that.  So we just use one.  Because EasyTIVA expects the CR/LF bytes
	 * to be present in what it reads back, we add those back on.
	 * 
	 * This method is synchronized, because in theory the main process() loop thread and the dataThread
	 * could both try and read simultaneously.  It's unlikely unless some sort of protocol mess has
	 * happened, but we sync on this method anyway.
	 * 
	 * @return a byte array containing the bytes from the line read from the BIS.  Including CR/LF
	 * @throws IOException if we fail to read anything.
	 */
	private synchronized byte[] recv() throws IOException {
		String nextLine=fromDevice.readLine();
		nextLine+="\r\n";	//Add back on the CR/LR that reader will have stripped off.
		byte[] b=nextLine.getBytes();
		toLog.write(b);
		toLog.flush();
		System.err.print(nextLine);
		return b;
	}
	
	private Thread dataThread;
	
	private boolean keepGoing;
	
	private void startDataThread() {
		if(dataThread==null) {
			dataThread=new DataThread();
			keepGoing=true;
			dataThread.setName("BIS reader thread");
			dataThread.start();
			System.err.println("Started the EasyTIVA thread");
			return;
		}
		//If we get here , data thread was not null.  This shouldn't really happen.
		log.error("startDataThread called but dataThread already not null");
		//Flag the existing one to stop.
		keepGoing=false;
		//And interrupt it - not really useful.
		dataThread.interrupt();
		//Make a new one
		dataThread=new DataThread();
		//And start it.
		dataThread.start();
	}
	
	class DataThread extends Thread {
		@Override
		public void run() {
			while(keepGoing && !isInterrupted()) {
				try {
					byte[] dataBytes=recv();
					toRequestor.write(dataBytes);
					toRequestor.flush();
//					System.err.println("Wrote "+new String(dataBytes)+" to the EasyTIVA");
				} catch (IOException ioe) {
					log.error("Failed to read from the BIS in data thread",ioe);
				}
				/*
				 * We don't really need a sleep in here because the publication time from the BIS is 5 seconds.
				 * But fundamentally, the read from the BIS blocks anyway - and as soon as it's get a line,
				 * we want to send it to the EasyTIVA anyway.  So we don't attempt to do anything in terms of timing here.
				 */
			}
			//If we get here, keepGoing is no longer true.
			log.info("data thread asked to stop.");
			return;
		}
	}

	@Override
	public SerialProvider getSerialProvider(int idx) {
		SerialProvider serialProvider=super.getSerialProvider(idx);
		//We only suppose ascii mode, and the settings are the same on both channels.
		serialProvider.setDefaultSerialSettings(9600, DataBits.Eight , Parity.None, StopBits.One, FlowControl.None);
		return serialProvider;
	}

	@Override
	protected long getMaximumQuietTime(int idx) {
		return Long.MAX_VALUE;	//For now...
	}

	@Override
	public void shutdown() {
		//Stop the data thread if it's running.
		if(dataThread!=null && dataThread.isAlive()) {
			keepGoing=false;
			try {
				Thread.sleep(6000);	//Long enough for a read loop to execute on the data thread. 
			} catch (InterruptedException ie) {
				//Oh well...
			}
		}
		try {
			toLog.flush();
			toLog.close();
		} catch (IOException ioe) {
			log.error("Failed to flush and close bis log file");
		}
		super.shutdown();
	}
	
	@Override
	protected String iconResourceName() {
		return "bis.png";
	}

}
