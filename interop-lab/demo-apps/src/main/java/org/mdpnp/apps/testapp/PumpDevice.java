package org.mdpnp.apps.testapp;

/**
 * This class holds a Device to represent a pump, so that pumps can be explicitly identified
 * in UI apps such as FROA (Closed Loop Control).  This is important for cases where we have
 * pumps with more than one head, and we want to represent the two heads as more than one device,
 * e.g. two devices with one head each.
 * 
 * @author HPWorkStation
 *
 */
public class PumpDevice {
	
	private int head;
	
	private Device d;
	
	private String metric;

	public String getMetric() {
		return metric;
	}

	public void setMetric(String metric) {
		this.metric = metric;
	}

	public PumpDevice(Device d) {
		this.d=d;
	}
	
	public void setHead(int head) {
		this.head=head;
	}
	
	public int getHead() {
		return head;
	}
	
	public Device getDevice() {
		return d;
	}

	@Override
	public boolean equals(Object other) {
		if( ! (other instanceof PumpDevice)) {
			return false;
		}
		PumpDevice otherPump=(PumpDevice)other;
		/*
		 * This is a little bit of a dubious use of the equals method.  If we are comparing
		 * the PumpDevice objects, we should really compare the UDI of the wrapped Device,
		 * and also compare the head - as clearly, head 1 is not head 2.  BUT, the only place
		 * we are using .equals so far is when removing a device from the combo box in FROA,
		 * and in that situation, if the wrapped Device with a matching UDI has been removed
		 * from the DeviceListModel, we want to remove all instances of PumpDevice with the
		 * matching UDI.  So making two PumpDevice instances equal if they have a matching
		 * UDI will hopefully do what we need.
		 */
		return otherPump.d.getUDI().equals(d.getUDI())/* &&  otherPump.head==head*/;
	}
	
	
	
	
	

}
