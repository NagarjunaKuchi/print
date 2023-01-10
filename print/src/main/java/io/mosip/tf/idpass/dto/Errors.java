package io.mosip.tf.idpass.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class Errors {
	String errorCode;
	String message;
}