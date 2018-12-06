package org.wiremock.webhooks;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.common.Notifier;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.PostServeAction;
import com.github.tomakehurst.wiremock.extension.responsetemplating.ResponseTemplateTransformer;
import com.github.tomakehurst.wiremock.http.HttpClientFactory;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import wiremock.org.apache.http.HttpResponse;
import wiremock.org.apache.http.client.HttpClient;
import wiremock.org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import wiremock.org.apache.http.client.methods.HttpUriRequest;
import wiremock.org.apache.http.entity.ByteArrayEntity;
import wiremock.org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static com.github.tomakehurst.wiremock.common.Exceptions.throwUnchecked;
import static com.github.tomakehurst.wiremock.common.LocalNotifier.notifier;
import static com.github.tomakehurst.wiremock.http.HttpClientFactory.getHttpRequestFor;
import static java.util.concurrent.TimeUnit.SECONDS;

public class Webhooks extends PostServeAction {

    private final ScheduledExecutorService scheduler;
    private final HttpClient httpClient;

    public Webhooks() {
        scheduler = Executors.newScheduledThreadPool(10);
        httpClient = HttpClientFactory.createClient();
    }

    @Override
    public String getName() {
        return "webhook";
    }

    @Override
    public void doAction(final ServeEvent serveEvent, final Admin admin, final Parameters parameters) {
        final WebhookDefinition definition = parameters.as(WebhookDefinition.class);
        final Notifier notifier = notifier();

        scheduler.schedule(
            new Runnable() {
                @Override
                public void run() {
                    HttpUriRequest request = buildRequest(
                            definition,
                            serveEvent.getRequest(),
                            serveEvent.getResponseDefinition(),
                            admin.getOptions().filesRoot(),
                            parameters
                    );

                    try {
                        HttpResponse response = httpClient.execute(request);
                        notifier.info(
                            String.format("Webhook %s request to %s returned status %s\n\n%s",
                                definition.getMethod(),
                                definition.getUrl(),
                                response.getStatusLine(),
                                EntityUtils.toString(response.getEntity())
                            )
                        );
                    } catch (IOException e) {
                        throwUnchecked(e);
                    }
                }
            },
            0L,
            SECONDS
        );
    }

    private static HttpUriRequest buildRequest(
            WebhookDefinition definition,
            Request request,
            ResponseDefinition responseDefinition,
            FileSource fs,
            Parameters parameters
    ) {
        HttpUriRequest httpRequest = getHttpRequestFor(
                definition.getMethod(),
                definition.getUrl().toString()
        );

        for (HttpHeader header: definition.getHeaders().all()) {
            httpRequest.addHeader(header.key(), header.firstValue());
        }

        if (definition.getMethod().hasEntity()) {
            HttpEntityEnclosingRequestBase entityRequest = (HttpEntityEnclosingRequestBase) httpRequest;
            ResponseDefinition respDef = ResponseDefinitionBuilder.like(responseDefinition)
                    .withBody(definition.getBody())
                    .build();

            byte[] body = new ResponseTemplateTransformer(false)
                    .transform(request, respDef, fs, parameters)
                    .getByteBody();
            entityRequest.setEntity(new ByteArrayEntity(body));
        }

        return httpRequest;
    }

    public static WebhookDefinition webhook() {
        return new WebhookDefinition();
    }
}
