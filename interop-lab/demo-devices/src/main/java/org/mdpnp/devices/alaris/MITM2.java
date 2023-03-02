package org.mdpnp.devices.alaris;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

import org.mdpnp.devices.serial.AbstractSerialDevice;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.rtiapi.data.EventLoop;

import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;

/**
 * A man in the middle, as simple as possible, between the EasyTiva and the Alaris.
 * @author HRVDeveloper
 *
 */
public class MITM2 extends AbstractSerialDevice {
	
	/**
	 * A reader to obtain commands from the EasyTIVA
	 */
	BufferedReader fromEasyTiva;
	
	/**
	 * A writer to send responses to the EasyTIVA
	 */
	BufferedWriter toEasyTiva;
	
	/**
	 * A reader to read responses from the Alaris
	 */
	BufferedReader fromAlaris;
	
	/**
	 * A writer to send commands to the Alaris
	 */
	BufferedWriter toAlaris;

	public MITM2(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
		this(subscriber, publisher, eventLoop, 2);
	}

	public MITM2(Subscriber subscriber, Publisher publisher, EventLoop eventLoop, int countSerialPorts) {
		super(subscriber, publisher, eventLoop, countSerialPorts);
		deviceIdentity.manufacturer = "ICE";
		deviceIdentity.model = "ET MITM Alaris";
		deviceIdentity.operating_system="OS";
		AbstractSimulatedDevice.randomUDI(deviceIdentity);
		super.writeDeviceIdentity();
		
	}

	@Override
	protected void doInitCommands(int idx) throws IOException {
		/*
		 * We'll just do absolutely nothing here for now.
		 */
		if(idx==0) {
			reportConnected("No Init Commands");
		}
	}

	@Override
	protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
		if(idx==0) {
			fromEasyTiva=new BufferedReader(new InputStreamReader(inputStream));
			toEasyTiva=new BufferedWriter(new OutputStreamWriter(outputStream));
		}
		if(idx==1) {
			fromAlaris=new BufferedReader(new InputStreamReader(inputStream));
			toAlaris=new BufferedWriter(new OutputStreamWriter(outputStream));
		}
		
		if(idx==0) {
			while(true) {
				if(fromEasyTiva==null || toAlaris==null || fromAlaris==null || toEasyTiva==null) {
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					continue;
				}
				break;
			}
			Coupled fromETtoPump=new Coupled(fromEasyTiva,toAlaris,"\r");
			fromETtoPump.setName("ReadEasyWritePump");
			Coupled fromPumpToET=new Coupled(fromAlaris, toEasyTiva, "\r");
			fromPumpToET.setName("ReadPumpWriteEasy");
			System.err.println("Starting coupled streams at "+System.currentTimeMillis());
			fromPumpToET.start();	//Start this first as the pump shouldn't be saying anything until it's been written to.
			fromETtoPump.start();	//Now start this.
			try {
				fromETtoPump.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		if(idx==1) {
			try {
				Thread.sleep(Long.MAX_VALUE);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

	}
	
	@Override
	protected long getMaximumQuietTime(int idx) {
		// TODO Auto-generated method stub
		return 5_000L;
	}

	@Override
	protected String iconResourceName() {
		// TODO Auto-generated method stub
		return "alaris_easytiva.png";
	}

	class Coupled extends Thread {
		BufferedReader r;
		BufferedWriter w;
		String e;
		
		Coupled(BufferedReader reader, BufferedWriter writer, String eol) {
			r=reader;
			w=writer;
			e=eol;
		}
		
		@Override
		public void run() {
			String s;
			try {
				while( (s=r.readLine())!=null ) {
					w.write(s+e);
				}
			} catch (IOException e) {
				System.err.println("Read or write exception in "+getName());
				e.printStackTrace();
			}
		}
	}

}
