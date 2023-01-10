package io.mosip.tf.idpass.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
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
 * Spring boot singleton component execution wrapper of
 * an IDPassReader instance
 */

@Component
public class IDPassReaderComponent
{
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
    private String reason= "signing";

    ObjectMapper mapper = new ObjectMapper();

    /**
     * Instantiates IDPassReader reader with a particular configuration
     *
     * @throws IDPassException Standard exception
     * @throws IOException Standard exception
     */
    public IDPassReaderComponent(IDPassliteConfig config)
            throws IDPassException, IOException
    {
        if (reader == null) {
        	try {
        	IDPassLite.initialize();
        	}catch (Exception e) {
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
            	KeySet keyset = KeySet.newBuilder()
        			    .setEncryptionKey(ByteString.copyFrom(encryptionkey))
        			    .setSignatureKey(ByteString.copyFrom(signaturekey))
        			    .addVerificationKeys(byteArray.newBuilder()
        			        .setTyp(byteArray.Typ.ED25519PUBKEY)
        			        .setVal(ByteString.copyFrom(publicVerificationKey)).build())
        			    .build();

            	byte[] rootkey = IDPassHelper.generateSecretSignatureKey();
            	Certificate rootcert = IDPassReader.generateRootCertificate(rootkey);
            	Certificates rootcerts = Certificates.newBuilder().addCert(rootcert).build();
        		Certificate childcert = IDPassReader.generateChildCertificate(rootkey, publicVerificationKey);
        		certchain = Certificates.newBuilder().addCert(childcert).build();
        		reader = new IDPassReader(keyset, rootcerts);
        		signaturePageURL = IDPassReaderComponent.class.getClassLoader().getResource("signaturepage.pdf");
            }catch (Exception e) {
				// TODO: handle exception
			}            
        }
    }

    /**
     * Returns a PNG image QR code representation as a byte[] array,
     * from the given inputs. The returned values are wrapped in a
     * DTO object to collate other computed values needed and pass
     * through other functions.
     *
     * @param cs The credential subject input json
     * @param pincode The IDPASS LITE pin code
     * @param photob64 A facial photo image
     * @return Returns Object data holder of computed values
     */
    public IDPassLiteDTO generateQrCode(String cs, String photob64, String pincode)
            throws IOException {

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

        } catch (NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
            return null;
        }

        Ident.Builder identBuilder = idfc.newIdentBuilder();

        identBuilder.setPin(/*pincode*/"1234");

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
            BufferedImage bi = Helper.toBufferedImage(card);
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(bi, "png", bos);
            qrcodeId = bos.toByteArray();
            ret.setQrCodeBytes(qrcodeId);
            ret.setSvg(card.asQRCodeSVG().getBytes(StandardCharsets.UTF_8));
            ret.setIdent(ident);
            writeStringUsingBufferedWritt_thenCorrect(qrcodeId, "qrcodeId");
            writeStringUsingBufferedWritt_thenCorrect(card.asQRCodeSVG().getBytes(StandardCharsets.UTF_8), "asQRCodeSVG");
            writeStringUsingBufferedWritt_thenCorrect(card.asQRCodeSVG(), "asQRCodeSVGString");
            
        } catch (IOException | IDPassException e) {
            return null;
        }

        return  ret;
    }

    /**
     * Call editor.idpass.org to generate ID PASS Lite PDF card
     * @param sd Session ID PASS Lite computed values holder
     * @return Returns pdf bytes array
     * @throws IOException Standard exception
     */

    public byte[] editorGenerate(IDPassLiteDTO sd)
            throws IOException
    {
        byte[] pdfbytes = null;
        Ident ident = sd.getIdent();

        ObjectNode fields = mapper.createObjectNode();
        fields.put("identification_no", ident.getUIN());
        // TODO: Submitted editor.idpass.org feature request
        // Description: https://drive.google.com/file/d/1ejz2hmMgi3iqNNT2Fppri3Et3GbXN9jK/view?usp=sharing
        //front.put("full_name", m_idfc.getFullName());
        fields.put("surname", ident.getSurName());
        fields.put("given_name", ident.getFullName());
        fields.put("sex",ident.getGender() == 1 ? "Female" : "Male");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(m_config.getDatePattern());
        if (ident.hasDateOfBirth()) {
            Date dob = ident.getDateOfBirth();
            String dobStr = String.format("%d/%d/%d",dob.getYear(),dob.getMonth(),dob.getDay());
            fields.put("date_of_birth", dobStr);
        }
        LocalDate issuanceDate = LocalDate.now();
        String issue_date = issuanceDate.format(formatter);
        fields.put("date_of_issue",issue_date);
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
        CloseableHttpClient httpClient = HttpClientBuilder.create().setSSLContext(context)
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        RestTemplate restTemplate = new RestTemplate(factory);
        String uri = m_config.getEditorUrl();
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<String, String>();
        headers.add("Content-Type", MediaType.APPLICATION_JSON.toString());
//        HttpEntity<String> request = new HttpEntity<String>(jsonPayload, headers);
        whenWriteStringUsingBufferedWritt_thenCorrect(jsonPayload);
//        String response = restTemplate.postForObject(uri, request, String.class);        
//        JsonNode node = mapper.readTree(response);
//        String blob = node.get("files").get("pdf").asText();
//        String b64 = blob.split(",")[1];
//        pdfbytes = CryptoUtil.decodeBase64(b64);
        
        InputStream is = IdentFields.class.getClassLoader().getResourceAsStream("response.json");
        Map<String, Object> jsonMap = mapper.readValue(is, Map.class);
        String[] data = jsonMap.get("pdf").toString().split(",");
        for (String string : data) {
        	System.out.println(string);
		}
        pdfbytes = CryptoUtil.decodeBase64(jsonMap.get("pdf").toString().split(",")[1]);
        whenWriteStringUsingBufferedWritter_thenCorrect(pdfbytes);
        /////////////

        return pdfbytes;
    }

    /**
     * This method is a modified from UinCardGeneratorImpl::generateUinCard
     * as this invokes a REST call to editor.idpass.org to generate the pdf
     * that is about to be send to MOSIP backend for signature
     *
     * @param in Template. Not used here
     * @param type Card type
     * @param password password
     * @param sd Session data computed values holder
     * @return Returns pdf bytes of signed pdf
     * @throws ApisResourceAccessException standard exception
     */

    public byte[] generateUinCard(InputStream in, UinCardType type, String password, IDPassLiteDTO sd)
            throws ApisResourceAccessException
    {
        byte[] pdfSignatured=null;
        try {
            // Calls editor.idpass.org REST API to generate initial PDF
            byte[] pdfbuf = editorGenerate(sd);
            Path tmp1 = Files.createTempFile(null,null);
            OutputStream tmp1os = new FileOutputStream(tmp1.toFile());
            tmp1os.write(pdfbuf);

            List<URL> pdfList = new ArrayList<>();
            pdfList.add(tmp1.toUri().toURL());
            pdfList.add(signaturePageURL);
            byte[] threepages = pdfGenerator.mergePDF(pdfList);
            tmp1.toFile().delete();

            PDFSignatureRequestDto request = new PDFSignatureRequestDto(5, 2, 232, 72, reason, 3, password);

            request.setApplicationId("KERNEL");
            request.setReferenceId("SIGN");
            request.setData(CryptoUtil.encodeBase64String(threepages));
            DateTimeFormatter format = DateTimeFormatter.ofPattern(env.getProperty(DATETIME_PATTERN));
            LocalDateTime localdatetime = LocalDateTime
                    .parse(DateUtils.getUTCCurrentDateTimeString(env.getProperty(DATETIME_PATTERN)), format);

            request.setTimeStamp(DateUtils.getUTCCurrentDateTimeString());
            RequestWrapper<PDFSignatureRequestDto> requestWrapper = new RequestWrapper<>();

            requestWrapper.setRequest(request);
            requestWrapper.setRequesttime(localdatetime);
            ResponseWrapper<?> responseWrapper;
            SignatureResponseDto signatureResponseDto;

            responseWrapper= (ResponseWrapper<?>)restClientService.postApi(ApiName.PDFSIGN, null, null,
                    requestWrapper, ResponseWrapper.class,MediaType.APPLICATION_JSON);


            if (responseWrapper.getErrors() != null && !responseWrapper.getErrors().isEmpty()) {
                ErrorDTO error = responseWrapper.getErrors().get(0);
                throw new PDFSignatureException(error.getMessage());
            }
            signatureResponseDto = mapper.readValue(mapper.writeValueAsString(responseWrapper.getResponse()),
                    SignatureResponseDto.class);

            pdfSignatured = CryptoUtil.decodeBase64(signatureResponseDto.getData());
            whenWriteStringUsingBufferedWritte_thenCorrect(pdfSignatured);

        } catch (IOException | PDFGeneratorException e) {
            throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
                    e.getMessage() + ExceptionUtils.getStackTrace(e));
        }
        catch (ApisResourceAccessException e) {
            e.printStackTrace();
        }

        return pdfSignatured;
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
    
    public void whenWriteStringUsingBufferedWritter_thenCorrect(byte[] payload) 
    		  throws IOException {
//    		    BufferedWriter writer = new BufferedWriter(new FileWriter("D:\\payload.txt"));
//    		    writer.write(payload);
//    		    
//    		    writer.close();
    		    
    		    OutputStream out = new FileOutputStream("D:\\pdfpayload.pdf");
    		    out.write(payload);
    		    out.close();
    		}
    
    public void whenWriteStringUsingBufferedWritte_thenCorrect(byte[] payload) 
  		  throws IOException {
//  		    BufferedWriter writer = new BufferedWriter(new FileWriter("D:\\payload.txt"));
//  		    writer.write(payload);
//  		    
//  		    writer.close();
  		    
  		    OutputStream out = new FileOutputStream("D:\\pdfpayloadSigned.pdf");
  		    out.write(payload);
  		    out.close();
  		}
    
    public void whenWriteStringUsingBufferedWritt_thenCorrect(String payload) 
    		  throws IOException {
    		    BufferedWriter writer = new BufferedWriter(new FileWriter("D:\\payload.txt"));
    		    writer.write(payload);
    		    
    		    writer.close();
    		    

    		}
    
    public void writeStringUsingBufferedWritt_thenCorrect(byte[] svg, String fileName) 
  		  throws IOException {
    	OutputStream out = new FileOutputStream("D:\\"+fileName);
		    out.write(svg);
		    out.close();
  		    

  		}
    
    public void writeStringUsingBufferedWritt_thenCorrect(String svg, String fileName) 
    		  throws IOException {
	    BufferedWriter writer = new BufferedWriter(new FileWriter("D:\\"+fileName));
	    writer.write(svg);
	    
	    writer.close();
  		    

    		}
}
