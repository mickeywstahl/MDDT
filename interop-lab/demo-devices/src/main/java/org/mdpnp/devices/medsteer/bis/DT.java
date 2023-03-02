package org.mdpnp.devices.medsteer.bis;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

public class DT {

	public DT() {
		// TODO Auto-generated constructor stub
	}
	
	public static void main(String args[]) {
		DateTimeFormatter formatter=new DateTimeFormatterBuilder().parseCaseInsensitive()
				.appendValue(ChronoField.MONTH_OF_YEAR,2)
				.appendLiteral('/')
				.appendValue(ChronoField.DAY_OF_MONTH,2)
				.appendLiteral('/')
				.appendValue(ChronoField.YEAR,4)
				.appendLiteral(' ')
				.appendValue(ChronoField.HOUR_OF_DAY,2)
				.appendLiteral(':')
				.appendValue(ChronoField.MINUTE_OF_HOUR,2)
				.appendLiteral(':')
				.appendValue(ChronoField.SECOND_OF_MINUTE)
				.toFormatter();
		LocalDateTime dt=LocalDateTime.now();
		System.err.println(formatter.format(dt));
	}

}
