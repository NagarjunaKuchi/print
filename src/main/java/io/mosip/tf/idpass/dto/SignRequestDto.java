package io.mosip.tf.idpass.dto;

import javax.validation.constraints.NotBlank;

import lombok.Data;

@Data
public class SignRequestDto {
	@NotBlank
	private String data;
}
