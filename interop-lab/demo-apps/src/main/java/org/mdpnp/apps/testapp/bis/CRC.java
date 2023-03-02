package org.mdpnp.apps.testapp.bis;

public class CRC {
	
	private String crc(String input) {
		final short initRegister = (short)0xffff;
		String message = input;
        byte[] messageBytes = message.getBytes();

        java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(messageBytes);
        short bitMask = (short)(1 << 15);

        // Process each message byte.
        int value = stream.read();
        short register = initRegister;
        while (value != -1) {
            byte element = (byte)value;

            register ^= ((short)element << 8);
            for (int i = 0; i < 8; i++) {
                if ((register & bitMask) != 0) {
                    register = (short)((register << 1) ^ 0x1021);
                }
                else {
                register <<= 1;
                }
            }
            value = stream.read();
        }

        // XOR the final register value.
        register ^= 0x0000;
        String hexValue = valueOf(register);
        hexValue = hexValue.toUpperCase();
        while(hexValue.length()!=4){
            hexValue = "0"+hexValue;
        }
        return hexValue;
	}
	
	private String valueOf(short number) {
        // Create a mask to isolate only the correct width of bits.
        long fullMask = (((1L << 15) - 1L) << 1) | 1L;
        return Long.toHexString(number & fullMask);
  }

	public static void main(String[] args) {
		CRC crc=new CRC();
		System.err.println(crc.crc("8002-51740"));

	}

}
