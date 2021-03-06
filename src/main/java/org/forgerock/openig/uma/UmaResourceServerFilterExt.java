/*
 * Copyright © 2017 ForgeRock, AS.
 *
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Portions Copyrighted 2017 Charan Mann
 *
 * openig-uma-ext: Created by Charan Mann on 6/29/17 , 10:56 PM.
 */

package org.forgerock.openig.uma;

import org.forgerock.http.Filter;
import org.forgerock.http.Handler;
import org.forgerock.http.header.Warning;
import org.forgerock.http.header.WarningHeader;
import org.forgerock.http.oauth2.OAuth2;
import org.forgerock.http.protocol.Form;
import org.forgerock.http.protocol.Request;
import org.forgerock.http.protocol.Response;
import org.forgerock.http.protocol.Status;
import org.forgerock.json.JsonValue;
import org.forgerock.openig.heap.GenericHeaplet;
import org.forgerock.openig.heap.HeapException;
import org.forgerock.services.context.Context;
import org.forgerock.util.AsyncFunction;
import org.forgerock.util.Function;
import org.forgerock.util.promise.NeverThrowsException;
import org.forgerock.util.promise.Promise;
import org.forgerock.util.promise.ResultHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static java.lang.String.format;
import static org.forgerock.http.header.WarningHeader.MISCELLANEOUS_WARNING;
import static org.forgerock.http.protocol.Response.newResponsePromise;
import static org.forgerock.http.protocol.Responses.newInternalServerError;
import static org.forgerock.json.JsonValue.*;
import static org.forgerock.openig.util.JsonValues.evaluated;
import static org.forgerock.openig.util.JsonValues.requiredHeapObject;
import static org.forgerock.util.Utils.closeSilently;

/**
 * An {@link UmaResourceServerFilter} implements a PEP (Policy Enforcement Point) and is responsible to ensure the
 * incoming requests (from requesting parties) all have a valid RPT (Request Party Token) with the required set of
 * scopes.
 * <p>
 * <pre>
 *     {@code {
 *           "type": "UmaFilter",
 *           "config": {
 *           "protectionApiHandler": "ClientHandler",
 *           "umaService": "UmaServiceExt",
 *           "scopes" : [
 *               "http://login.example.com/scopes/view"
 *           ]
 *           }
 *       }
 *     }
 * </pre>
 */
public class UmaResourceServerFilterExt implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(UmaResourceServerFilterExt.class);

    private final UmaSharingServiceExt umaService;
    private final Handler protectionApiHandler;
    private final String realm;
    private final List<Object> scopes;

    /**
     * Constructs a new UmaResourceServerFilter.
     *
     * @param umaService           core service to use
     * @param protectionApiHandler protectionApiHandler to use when interacting with introspection and permission request endpoints
     * @param realm                UMA realm name (can be {@code null})
     */
    public UmaResourceServerFilterExt(final UmaSharingServiceExt umaService,
                                      final Handler protectionApiHandler,
                                      final String realm, final List<Object> scopes) {
        this.umaService = umaService;
        this.protectionApiHandler = protectionApiHandler;
        this.realm = realm;
        this.scopes = scopes;
    }

    @Override
    public Promise<Response, NeverThrowsException> filter(final Context context,
                                                          final Request request,
                                                          final Handler next) {

        try {
            // Find a Share for this request
            final ShareExt share = umaService.findShare(request);
            String rpt = OAuth2.getBearerAccessToken(request.getHeaders().getFirst("Authorization"));

            // Is there an RPT ?
            if (rpt != null) {
                // Validate the token
                return introspectToken(context, rpt, share.getPAT())
                        .thenAsync(new VerifyScopesAsyncFunction(share, context, request, next));
            }

            // Error case: ask for a ticket
            return ticket(context, share, request);

        } catch (UmaException e) {
            logger.error("An error occurred while looking for a UMA share", e);
            // No share found
            // Make sure we return a 404
            return newResponsePromise(e.getResponse().setStatus(Status.NOT_FOUND));
        }
    }

    /**
     * Call the UMA Permission Registration Endpoint to register a requested permission with the authorization server.
     * <p>
     * <p>If the registration succeed, the obtained opaque ticket is returned to the client with an additional {@literal
     * WWW-Authenticate} header:
     * <p>
     * <pre>
     *     {@code HTTP/1.1 401 Unauthorized
     *       WWW-Authenticate: UMA realm="example",
     *                             as_uri="https://as.example.com",
     *                             ticket="016f84e8-f9b9-11e0-bd6f-0021cc6004de"
     *     }
     * </pre>
     * <p>
     * Otherwise, a {@literal 403 Forbidden} response with an informative {@literal Warning} header is produced.
     *
     * @param context  Context chain used to keep a relationship between requests (tracking)
     * @param share    represents protection information about the requested resource
     * @param incoming request used to infer the set of permissions to ask
     * @return an asynchronous {@link Response}
     * @see <a href="https://docs.kantarainitiative.org/uma/ed/oauth-uma-federated-authz-2.0-06.html#permission-endpoint">
     * Permission Endpoint</a>
     */
    private Promise<Response, NeverThrowsException> ticket(final Context context,
                                                           final ShareExt share,
                                                           final Request incoming) {
        Request request = new Request();
        request.setMethod("POST");
        request.setUri(umaService.getPermissionEndpoint());
        request.getHeaders().put("Authorization", format("Bearer %s", share.getPAT()));
        request.getHeaders().put("Accept", "application/json");
        request.setEntity(createPermissionRequest(share, incoming).asMap());

        return protectionApiHandler.handle(context, request)
                .thenAlways(request::close)
                .then(new TicketResponseFunction());
    }

    /**
     * Builds the resource registration {@link Request}'s JSON content.
     *
     * @param share   represents protection information about the requested resource
     * @param request request used to infer the set of permissions to ask
     * @return a JSON structure that represents a resource set registration
     * @see <a href="https://docs.kantarainitiative.org/uma/ed/oauth-uma-federated-authz-2.0-06.html#permission-endpoint">
     * Permission Endpoint</a>
     */
    private JsonValue createPermissionRequest(final ShareExt share, final Request request) {

        return json(object(field("resource_id", share.getResourceId()),
                field("resource_scopes", array(scopes.toArray(new Object[scopes.size()])))));
    }

    private Promise<Response, NeverThrowsException> introspectToken(final Context context,
                                                                    final String token,
                                                                    final String pat) {
        Request request = new Request();
        request.setUri(umaService.getIntrospectionEndpoint());
        request.getHeaders().put("Authorization", format("Bearer %s", pat));
        request.getHeaders().put("Accept", "application/json");

        Form query = new Form();
        query.putSingle("token", token);
        query.toRequestEntity(request);

        return protectionApiHandler.handle(context, request)
                                   .thenAlways(request::close);
    }

    /**
     * Creates and initializes an UMA resource server filter in a heap environment.
     */
    public static class Heaplet extends GenericHeaplet {

        @Override
        public Object create() throws HeapException {
            UmaSharingServiceExt service = config.get("umaService")
                    .required()
                    .as(requiredHeapObject(heap, UmaSharingServiceExt.class));
            Handler handler = config.get("protectionApiHandler").required().as(requiredHeapObject(heap, Handler.class));
            String realm = config.get("realm").as(evaluated()).defaultTo("uma").asString();
            List<Object> scopes = config.get("scopes").as(evaluated()).asList();
            return new UmaResourceServerFilterExt(service, handler, realm, scopes);
        }
    }

    private class VerifyScopesAsyncFunction implements AsyncFunction<Response, Response, NeverThrowsException> {
        private final ShareExt share;
        private final Context context;
        private final Request request;
        private final Handler next;

        public VerifyScopesAsyncFunction(final ShareExt share,
                                         final Context context,
                                         final Request request,
                                         final Handler next) {
            this.share = share;
            this.context = context;
            this.request = request;
            this.next = next;
        }

        @Override
        public Promise<Response, NeverThrowsException> apply(final Response token) {

            if (Status.OK == token.getStatus()) {
                JsonValue value = null;
                try {
                    value = json(token.getEntity().getJson());
                } catch (IOException e) {
                    logger.debug("Cannot extract JSON from token introspection response, possibly malformed JSON");
                    return newResponsePromise(newInternalServerError(e));
                }
                if (value.get("active").asBoolean()) {
                    // Got a valid token
                    // Need to verify embed scopes against required scopes

                    if (getScopes(value, share.getResourceId()).containsAll(scopes)) {
                        // All required scopes are present, continue the request processing
                        return next.handle(context, request);
                    }

                    logger.trace("Insufficient scopes encoded in RPT, asking for a new ticket");
                    // Not all of the required scopes are in the token
                    // Error case: ask for a ticket, append an error code
                    return ticket(context, share, request)
                            .thenOnResult(new ResultHandler<Response>() {
                                @Override
                                public void handleResult(final Response response) {

                                    // Update the Authorization header with a proper error code
                                    String authorization = response.getHeaders()
                                            .getFirst("WWW-Authenticate");
                                    if (authorization != null) {
                                        authorization = authorization.concat(", error=\"insufficient_scope\"");
                                        response.getHeaders().put("WWW-Authenticate", authorization);
                                    }
                                }
                            });
                }
            }

            // Error case: ask for a ticket
            return ticket(context, share, request);
        }

        private List<String> getScopes(final JsonValue value, final String resourceId) {
            for (JsonValue permission : value.get("permissions")) {
                if (resourceId.equals(permission.get("resource_id").asString())) {
                    return permission.get("resource_scopes").asList(String.class);
                }
            }
            return Collections.emptyList();
        }
    }

    private class TicketResponseFunction implements Function<Response, Response, NeverThrowsException> {
        @Override
        public Response apply(final Response response) {
            try {
                if (Status.CREATED == response.getStatus()) {
                    // Create a new response with authenticate header and status code
                    try {
                        JsonValue value = json(response.getEntity().getJson());
                        Response unauthorized = new Response(Status.UNAUTHORIZED);
                        String ticket = value.get("ticket").asString();
                        unauthorized.getHeaders().put("WWW-Authenticate",
                                format("UMA realm=\"%s\", as_uri=\"%s\", ticket=\"%s\"",
                                        realm,
                                        umaService.getAuthorizationServer(),
                                        ticket));
                        return unauthorized;
                    } catch (IOException e) {
                        // JSON parsing exception
                        // Do not process them here, handle them in the later catch-all block
                        logger.debug("Cannot extract JSON from ticket response, possibly malformed JSON", e);
                    }
                } else {
                    logger.debug("Got a {} Response from '{}', was expecting a 201 Created.",
                            response.getStatus(),
                            umaService.getPermissionEndpoint());
                }

                // Properly handle 400 errors and UMA error codes
                // The PAT may need to be refreshed
                Response forbidden = new Response(Status.FORBIDDEN);
                forbidden.getHeaders().put(new WarningHeader(new Warning(MISCELLANEOUS_WARNING,
                        "-",
                        "UMA Authorization Error: "+ response.getEntity())));
                return forbidden;
            } finally {
                // Close previous response object
                closeSilently(response);
            }
        }
    }
}