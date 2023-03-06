package io.mosip.id.pass.test.controller;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.mosip.id.pass.test.TestBootApplication;
import io.mosip.tf.idpass.controller.Print;
import io.mosip.tf.idpass.model.Event;
import io.mosip.tf.idpass.model.EventModel;
import io.mosip.tf.idpass.service.PrintService;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestBootApplication.class)
@AutoConfigureMockMvc
public class PrintControllerTest {

	@InjectMocks
	private Print printController;

	@Mock
	PrintService printService;

	private MockMvc mockMvc;

	Gson gson = new GsonBuilder().serializeNulls().create();

	String reqJson;

	EventModel credentialEvent;

	String reqCredentialEventJson;

	@Before
	public void setup() throws Exception {
		MockitoAnnotations.initMocks(this);
		this.mockMvc = MockMvcBuilders.standaloneSetup(printController).build();
		credentialEvent = new EventModel();
		Event event=new Event();
		event.setId("test123");
		credentialEvent.setEvent(event);
		reqCredentialEventJson = gson.toJson(credentialEvent);

	}

	@Test
	public void testHandleSubscribeEventSuccess() throws Exception {
		byte[] pdfbytes = "pdf".getBytes();
		Mockito.when(printService.generateCard(Mockito.any())).thenReturn(true);
		mockMvc.perform(MockMvcRequestBuilders.post("/print/callback/notifyPrint")
				.contentType(MediaType.APPLICATION_JSON_VALUE).content(reqCredentialEventJson.getBytes()))
				.andExpect(status().isOk());
	}

}
