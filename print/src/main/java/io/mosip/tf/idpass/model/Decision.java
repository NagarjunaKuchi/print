package io.mosip.tf.idpass.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.mosip.tf.idpass.constant.Match;
import lombok.Data;

@Data
public class Decision {
	
	private Match match;
	private List<String> errors;
	private Map<String, String> analyticsInfo;
	
	public Decision() {
		this.errors = new ArrayList<>();
		this.analyticsInfo = new HashMap<String, String>();
	}

}
