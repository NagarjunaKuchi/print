package io.mosip.tf.idpass.service;

import io.mosip.tf.idpass.model.EventModel;

public interface PrintService {
    
	/**
	 * Get the card
	 * 
	 * 
	 * @param eventModel
	 * @return
	 * @throws Exception
	 */
	public boolean generateCard(EventModel eventModel) throws Exception;

	// Map<String, byte[]> getDocuments(String credentialSubject, String sign,
	// String cardType,
	// boolean isPasswordProtected);
	
}