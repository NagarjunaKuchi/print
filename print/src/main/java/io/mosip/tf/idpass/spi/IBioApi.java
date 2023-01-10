package io.mosip.tf.idpass.spi;

import io.mosip.tf.idpass.entity.BIR;
import io.mosip.tf.idpass.entity.MatchDecision;
import io.mosip.tf.idpass.model.KeyValuePair;
import io.mosip.tf.idpass.model.QualityScore;
import io.mosip.tf.idpass.model.Response;

/**
 * The Interface IBioApi.
 * 
 * @author Sanjay Murali
 * @author Manoj SP
 * 
 */
public interface IBioApi {

	/**
	 * It checks the quality of the provided biometric image and render the
	 * respective quality score.
	 *
	 * @param sample the sample
	 * @param flags  the flags
	 * @return the response
	 */
	Response<QualityScore> checkQuality(BIR sample, KeyValuePair[] flags);

	/**
	 * It compares the biometrics and provide the respective matching scores.
	 *
	 * @param sample  the sample
	 * @param gallery the gallery
	 * @param flags   the flags
	 * @return the response
	 */
	Response<MatchDecision[]> match(BIR sample, BIR[] gallery, KeyValuePair[] flags);

	/**
	 * Extract template.
	 *
	 * @param sample the sample
	 * @param flags  the flags
	 * @return the response
	 */
	Response<BIR> extractTemplate(BIR sample, KeyValuePair[] flags);

	/**
	 * It segment the single biometric image into multiple biometric images. Eg:
	 * Split the thumb slab into multiple fingers
	 *
	 * @param sample the sample
	 * @param flags  the flags
	 * @return the response
	 */
	Response<BIR[]> segment(BIR sample, KeyValuePair[] flags);
}
