package io.mosip.tf.idpass.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.tf.idpass.constant.ApiName;
import io.mosip.tf.idpass.constant.PDFGeneratorExceptionCodeConstant;
import io.mosip.tf.idpass.constant.UinCardType;
import io.mosip.tf.idpass.core.http.RequestWrapper;
import io.mosip.tf.idpass.core.http.ResponseWrapper;
import io.mosip.tf.idpass.dto.ErrorDTO;
import io.mosip.tf.idpass.dto.PDFSignatureRequestDto;
import io.mosip.tf.idpass.dto.SignatureResponseDto;
import io.mosip.tf.idpass.exception.ApisResourceAccessException;
import io.mosip.tf.idpass.exception.PDFGeneratorException;
import io.mosip.tf.idpass.exception.PDFSignatureException;
import io.mosip.tf.idpass.service.PrintRestClientService;
import io.mosip.tf.idpass.spi.PDFGenerator;
import io.mosip.tf.idpass.util.RestApiClient.IdPassTokenRequest;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.idpass.lite.proto.Certificate;
import org.api.proto.Certificates;
import org.api.proto.Ident;
import org.api.proto.KeySet;
import org.api.proto.byteArray;
import org.idpass.lite.Card;
import org.idpass.lite.IDPassHelper;
import org.idpass.lite.IDPassLite;
import org.idpass.lite.IDPassReader;
import org.idpass.lite.exceptions.IDPassException;
import org.idpass.lite.proto.Date;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.net.ssl.SSLContext;

import static io.mosip.tf.idpass.service.impl.PrintServiceImpl.DATETIME_PATTERN;

import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import com.github.jaiimageio.jpeg2000.impl.J2KImageReader;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Spring boot singleton component execution wrapper of an IDPassReader instance
 */

@Component
public class IDPassReaderComponent {
	private Certificates certchain;
	static private FileHandler fileTxt;
	static private SimpleFormatter formatterTxt;
	private final static Logger LOGGER = Logger.getLogger(IDPassReaderComponent.class.getName());

	private static URL signaturePageURL;

	public static IDPassReader reader;

	@Autowired
	private PDFGenerator pdfGenerator;

	@Autowired
	IDPassliteConfig m_config;

	@Autowired
	private Environment env;

	@Autowired
	private PrintRestClientService<Object> restClientService;

	// @Value("${mosip.registration.processor.print.service.uincard.signature.reason}")
	private String reason = "signing";

	ObjectMapper mapper = new ObjectMapper();

	/**
	 * Instantiates IDPassReader reader with a particular configuration
	 *
	 * @throws IDPassException Standard exception
	 * @throws IOException     Standard exception
	 */
	public IDPassReaderComponent(IDPassliteConfig config) throws IDPassException, IOException {
		if (reader == null) {
			try {
				IDPassLite.initialize();
			} catch (Exception e) {
				e.printStackTrace();
			}
			LOGGER.setLevel(Level.INFO);
			fileTxt = new FileHandler("idpasslite.log", true);
			formatterTxt = new SimpleFormatter();
			fileTxt.setFormatter(formatterTxt);
			LOGGER.addHandler(fileTxt);
			try {
				byte[] encryptionkey = IDPassHelper.generateEncryptionKey();
				byte[] signaturekey = IDPassHelper.generateSecretSignatureKey();
				byte[] publicVerificationKey = IDPassHelper.getPublicKey(signaturekey);
				
				KeySet keyset = KeySet.newBuilder().setEncryptionKey(ByteString.copyFrom(encryptionkey))
						.setSignatureKey(ByteString.copyFrom(signaturekey))
						.addVerificationKeys(byteArray.newBuilder().setTyp(byteArray.Typ.ED25519PUBKEY)
								.setVal(ByteString.copyFrom(publicVerificationKey)).build())
						.build();

				byte[] rootkey = IDPassHelper.generateSecretSignatureKey();
				Certificate rootcert = IDPassReader.generateRootCertificate(rootkey);
				Certificates rootcerts = Certificates.newBuilder().addCert(rootcert).build();
				Certificate childcert = IDPassReader.generateChildCertificate(rootkey, publicVerificationKey);
				certchain = Certificates.newBuilder().addCert(childcert).build();
				reader = new IDPassReader(keyset, rootcerts);
				signaturePageURL = IDPassReaderComponent.class.getClassLoader().getResource("signaturepage.pdf");
			} catch (Exception e) {
				// TODO: handle exception
			}
		}
	}

	/**
	 * Returns a PNG image QR code representation as a byte[] array, from the given
	 * inputs. The returned values are wrapped in a DTO object to collate other
	 * computed values needed and pass through other functions.
	 *
	 * @param cs       The credential subject input json
	 * @param pincode  The IDPASS LITE pin code
	 * @param photob64 A facial photo image
	 * @return Returns Object data holder of computed values
	 */
	public IDPassLiteDTO generateQrCode(String cs, String photob64, String pincode) throws IOException {

		LOGGER.info(cs);
		LOGGER.info(pincode);

		IDPassLiteDTO ret = new IDPassLiteDTO();
		IdentFieldsConstraint idfc = null;

		try {
			idfc = (IdentFieldsConstraint) IdentFields.parse(cs, IdentFieldsConstraint.class);
			ret.setIdfc(idfc);
			if (idfc == null || !idfc.isValid()) { // in terms of identfieldsconstraint.json
				return null;
			}

		} catch (NoSuchMethodException | IllegalAccessException | InstantiationException
				| InvocationTargetException e) {
			return null;
		}

		Ident.Builder identBuilder = idfc.newIdentBuilder();

		identBuilder.setPin("1234");

		String imageType = photob64.split(",")[0];
		LOGGER.info(imageType);
		byte[] photo = CryptoUtil.decodeBase64(photob64.split(",")[1]);
		if (/* imageType.equals("data:image/x-jp2;base64") */ true) { /// TODO: Already raised this issue
			photo = convertJ2KToJPG(photo);
		}

		if (photo != null) {
			identBuilder.setPhoto(ByteString.copyFrom(photo));
			ret.setFacePhotob64(CryptoUtil.encodeBase64String(photo));
		}

		/* Populate Ident fields from idf object */

		Ident ident = identBuilder.build();
		byte[] qrcodeId = null;

		try {
			Card card = reader.newCard(ident, certchain);
			System.out.println(card.verifyCardSignature());
			System.out.println(card.verifyCertificate());
			//System.out.println(card.authenticateWithPIN("1234"));
			
			BufferedImage bi = Helper.toBufferedImage(card);

			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			ImageIO.write(bi, "png", bos);
			qrcodeId = bos.toByteArray();
			ret.setQrCodeBytes(qrcodeId);
			ret.setSvg(card.asQRCodeSVG().getBytes());
			ret.setIdent(ident);
		} catch (IOException | IDPassException e) {
			return null;
		}

		return ret;
	}

	/**
	 * Call editor.idpass.org to generate ID PASS Lite PDF card
	 * 
	 * @param sd Session ID PASS Lite computed values holder
	 * @return Returns pdf bytes array
	 * @throws IOException Standard exception
	 */

	public byte[] editorGenerate(IDPassLiteDTO sd) throws IOException {
		byte[] pdfbytes = null;
		Ident ident = sd.getIdent();
		
		ObjectNode fields = mapper.createObjectNode();
		fields.put("identification_no", ident.getUIN());
		fields.put("surname", ident.getFullName().split(" ")[1]);
		fields.put("given_name", ident.getFullName().split(" ")[0]);
		fields.put("sex", ident.getGender() == 1 ? "Female" : "Male");
		fields.put("nationality","INDIAN");
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern(m_config.getDatePattern());
		if (ident.hasDateOfBirth()) {
			Date dob = ident.getDateOfBirth();
			String dobStr = String.format("%d/%d/%d", dob.getYear(), dob.getMonth(), dob.getDay());
			fields.put("date_of_birth", dobStr);
		}
		LocalDate issuanceDate = LocalDate.now();
		String issue_date = issuanceDate.format(formatter);
		fields.put("date_of_issue", issue_date);
		String exp = issuanceDate.plusYears(m_config.getExpireYears()).format(formatter);
		fields.put("date_of_expiry", exp);
		fields.put("profile_svg_3", "data:image/jpeg;base64," + sd.getFacePhotob64());
		String svgqrcode = CryptoUtil.encodeBase64String(sd.getSvg());
		fields.put("qrcode_svg_15", "data:image/svg+xml;base64," + svgqrcode);
		ObjectNode payload = mapper.createObjectNode();
		payload.put("create_qr_code", false);
		payload.set("fields", fields);

		String jsonPayload = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);

		//////////////
		SSLContext context = null;
		try {
			context = SSLContext.getInstance("TLSv1.2");
			context.init(null, null, null);
		} catch (NoSuchAlgorithmException | KeyManagementException e) {
			e.printStackTrace();
		}
		CloseableHttpClient httpClient = HttpClientBuilder.create().setSSLContext(context).build();
		HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
		RestTemplate restTemplate = new RestTemplate(factory);
		String uri = m_config.getEditorUrl();
		MultiValueMap<String, String> headers = new LinkedMultiValueMap<String, String>();
		headers.add("Content-Type", MediaType.APPLICATION_JSON.toString());
		headers.add("Authorization", "Token " + getIdPassToken());
        HttpEntity<String> request = new HttpEntity<String>(jsonPayload, headers);
        String response = restTemplate.postForObject(uri, request, String.class);        
        JsonNode node = mapper.readTree(response);
        String blob = node.get("files").get("pdf").asText();
        String b64 = blob.split(",")[1];
        pdfbytes = CryptoUtil.decodeBase64(b64);
		return pdfbytes;
	}

	/**
	 * This method is a modified from UinCardGeneratorImpl::generateUinCard as this
	 * invokes a REST call to editor.idpass.org to generate the pdf that is about to
	 * be send to MOSIP backend for signature
	 *
	 * @param in       Template. Not used here
	 * @param type     Card type
	 * @param password password
	 * @param sd       Session data computed values holder
	 * @return Returns pdf bytes of signed pdf
	 * @throws ApisResourceAccessException standard exception
	 */

	public byte[] generateUinCard(InputStream in, UinCardType type, String password, IDPassLiteDTO sd)
			throws ApisResourceAccessException {
		try {
			return editorGenerate(sd);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}

	// Notes: copied from 'mosip-functional-tests' repo
	private static byte[] convertJ2KToJPG(byte[] jp2Data) {
		byte[] jpgImg = null;
		J2KImageReader j2kImageReader = new J2KImageReader(null);
		try {
			j2kImageReader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(jp2Data)));
			ImageReadParam imageReadParam = j2kImageReader.getDefaultReadParam();
			BufferedImage image = j2kImageReader.read(0, imageReadParam);
			ByteArrayOutputStream imgBytes = new ByteArrayOutputStream();
			ImageIO.write(image, "JPG", imgBytes);
			jpgImg = imgBytes.toByteArray();
		} catch (IOException e) {
			return null;
		}

		return jpgImg;
	}

	
	private String getIdPassToken() {
		IdPassTokenRequest tokenRequest = new IdPassTokenRequest();
		tokenRequest.setUsername("tfadmin");
		tokenRequest.setPassword("Techno@123");
		Gson gson = new Gson();
		HttpClient httpClient = HttpClientBuilder.create().build();
		HttpPost post = new HttpPost("https://idpass.technoforte.co.in/api/v1/auth-token/");
		try {
			StringEntity postingString = new StringEntity(gson.toJson(tokenRequest));
			post.setEntity(postingString);
			post.setHeader("Content-type", "application/json");
			HttpResponse response = httpClient.execute(post);
			org.apache.http.HttpEntity entity = response.getEntity();
			Map<String, Object> map 
			  = mapper.readValue(EntityUtils.toString(entity), new TypeReference<Map<String,Object>>(){});
			return (String) map.get("token");			
		}catch (Exception e) {
			// TODO: handle exception
		}
		
		return "";
	}
	

	public class IdPassTokenRequest{
		public String getUsername() {
			return username;
		}
		public void setUsername(String username) {
			this.username = username;
		}
		public String getPassword() {
			return password;
		}
		public void setPassword(String password) {
			this.password = password;
		}
		String username;
		String password;
	}

}


