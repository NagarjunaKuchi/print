package io.mosip.tf.idpass.dto;

import java.io.Serializable;
import java.util.List;

import io.mosip.tf.idpass.dto.BaseRestResponseDTO;
import io.mosip.tf.idpass.dto.ErrorDTO;
import lombok.Data;

@Data
public class VidResponseDTO extends BaseRestResponseDTO implements Serializable{
	
	private static final long serialVersionUID = -3604571018699722626L;

	private String str;
	
	private String metadata;
	
	private VidResDTO response;
	
	private List<ErrorDTO> errors;

}
