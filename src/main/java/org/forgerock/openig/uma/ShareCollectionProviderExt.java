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

import org.forgerock.api.annotations.*;
import org.forgerock.api.enums.CreateMode;
import org.forgerock.api.enums.QueryType;
import org.forgerock.api.enums.Stability;
import org.forgerock.http.oauth2.OAuth2;
import org.forgerock.http.protocol.Form;
import org.forgerock.http.protocol.Request;
import org.forgerock.http.protocol.Status;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.*;
import org.forgerock.json.resource.http.HttpContext;
import org.forgerock.services.context.Context;
import org.forgerock.util.Function;
import org.forgerock.util.promise.NeverThrowsException;
import org.forgerock.util.promise.Promise;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static java.lang.String.format;
import static org.forgerock.json.JsonValue.*;
import static org.forgerock.json.resource.Responses.newQueryResponse;
import static org.forgerock.json.resource.Responses.newResourceResponse;
import static org.forgerock.util.promise.Promises.newResultPromise;
import static org.forgerock.util.query.QueryFilter.alwaysTrue;

/**
 * A {@link ShareCollectionProvider} is the CREST-based endpoint responsible for managing (creating, deleting, ...)
 * {@link Share} objects.
 * <p>
 * <p>Supported operations: {@literal CREATE}, {@literal READ}, {@literal DELETE}
 * and {@literal QUERY} (simple shares list, no filtering).
 */
@CollectionProvider(details = @Handler(id = "share",
        resourceSchema = @Schema(schemaResource = "share.json", id = "share"),
        title = "i18n:#service.title",
        description = "i18n:#service.desc",
        mvccSupported = false))
class ShareCollectionProviderExt implements CollectionResourceProvider {

    private final UmaSharingServiceExt service;

    /**
     * Constructs a new CREST endpoint for managing {@linkplain Share shares}.
     *
     * @param service delegating service
     */
    public ShareCollectionProviderExt(final UmaSharingServiceExt service) {
        this.service = service;
    }

    private static JsonValue asJson(final ShareExt share) {
        return json(object(
                field("resourceURI", share.getRequestURI()),
                field("user_access_policy_uri", share.getPolicyURI()),
                field("pat", share.getPAT()),
                field("resource_id", share.getResourceId()),
                field("userId", share.getUserId()),
                field("realm", share.getRealm()),
                field("client_id", share.getClientId())));
    }

    @Override
    @Create(operationDescription = @Operation(description = "i18n:#create.desc",
            stability = Stability.EVOLVING,
            errors = {
                    @ApiError(id = "NotSupported",
                            code = 401,
                            description = "i18n:#not-supported.desc"),
                    @ApiError(id = "BadRequest",
                            code = 400,
                            description = "i18n:#bad-request.desc")
            }),
            modes = CreateMode.ID_FROM_SERVER)
    public Promise<ResourceResponse, ResourceException> createInstance(final Context context,
                                                                       final CreateRequest request) {
        if (request.getNewResourceId() != null) {
            return new NotSupportedException("Only POST-style of instance creation are supported").asPromise();
        }

        final String userId = introspectToken(context);
        if (null == userId) {
            return new BadRequestException("Missing or expired PAT in request").asPromise();
        }

        return service.createShare(context, request, userId)
                .then(new Function<ShareExt, ResourceResponse, ResourceException>() {
                    @Override
                    public ResourceResponse apply(final ShareExt share) throws ResourceException {
                        return newResourceResponse(share.getId(), null, asJson(share));
                    }
                }, new Function<UmaException, ResourceResponse, ResourceException>() {
                    @Override
                    public ResourceResponse apply(final UmaException exception) throws ResourceException {
                        throw new BadRequestException("Failed to create a share, Reason: " + exception.getMessage(), exception);
                    }
                });
    }

    @Override
    @Delete(operationDescription = @Operation(description = "i18n:#delete.desc",
            stability = Stability.EVOLVING,
            errors = {@ApiError(id = "NotFound",
                    code = 404,
                    description = "i18n:#not-found.desc")}))
    public Promise<ResourceResponse, ResourceException> deleteInstance(final Context context,
                                                                       final String resourceId,
                                                                       final DeleteRequest request) {
        final String userId = introspectToken(context);
        if (null == userId) {
            return new BadRequestException("Missing or expired PAT in request").asPromise();
        }

//        ShareExt share = service.removeShare(context, request, resourceId, userId);
//        if (share == null) {
//            return new NotFoundException(format("Share %s is unknown", resourceId)).asPromise();
//        }
//
//        //TODO Delete from OpenAM too
//        return newResultPromise(newResourceResponse(resourceId, null, asJson(share)));

        return service.removeShare(context, request, resourceId, userId)
                .then(new Function<ShareExt, ResourceResponse, ResourceException>() {
                    @Override
                    public ResourceResponse apply(final ShareExt share) throws ResourceException {
                        return newResourceResponse(share.getId(), null, asJson(share));
                    }
                }, new Function<UmaException, ResourceResponse, ResourceException>() {
                    @Override
                    public ResourceResponse apply(final UmaException exception) throws ResourceException {
                        throw new BadRequestException("Failed to remove a share, Reason: " + exception.getMessage(), exception);
                    }
                });
    }

    @Override
    public Promise<ResourceResponse, ResourceException> patchInstance(final Context context,
                                                                      final String resourceId,
                                                                      final PatchRequest request) {
        return new NotSupportedException().asPromise();
    }

    @Override
    @Query(operationDescription = @Operation(description = "i18n:#query.desc",
            stability = Stability.EVOLVING,
            errors = {@ApiError(id = "NotSupported",
                    code = 401,
                    description = "i18n:#not-supported.desc")}),
            type = QueryType.FILTER)
    public Promise<QueryResponse, ResourceException> queryCollection(final Context context,
                                                                     final QueryRequest request,
                                                                     final QueryResourceHandler handler) {

        // Reject queries with query ID, provided expressions and non "true" filter
        if (request.getQueryId() != null
                || request.getQueryExpression() != null
                || !alwaysTrue().equals(request.getQueryFilter())) {
            return new NotSupportedException("Only accept queries with filter=true").asPromise();
        }

        final String userId = introspectToken(context);
        if (null == userId) {
            return new BadRequestException("Missing or expired PAT in request").asPromise();
        }

        for (ShareExt share : service.listShares(userId)) {
            handler.handleResource(newResourceResponse(share.getId(), null, asJson(share)));
        }

        return newResultPromise(newQueryResponse());
    }

    @Override
    @Read(operationDescription = @Operation(description = "i18n:#read.desc",
            stability = Stability.EVOLVING))
    public Promise<ResourceResponse, ResourceException> readInstance(final Context context,
                                                                     final String resourceId,
                                                                     final ReadRequest request) {
        final String userId = introspectToken(context);
        if (null == userId) {
            return new BadRequestException("Missing or expired PAT in request").asPromise();
        }

        ShareExt share = service.getShare(resourceId, userId);

        if (null == share) {
            return new NotFoundException(format("Share %s is unknown", resourceId)).asPromise();
        }
        return newResultPromise(newResourceResponse(resourceId, null, asJson(share)));
    }

    @Override
    public Promise<ResourceResponse, ResourceException> updateInstance(final Context context,
                                                                       final String resourceId,
                                                                       final UpdateRequest request) {
        return new NotSupportedException().asPromise();
    }

    @Override
    public Promise<ActionResponse, ResourceException> actionCollection(final Context context,
                                                                       final ActionRequest request) {
        return new NotSupportedException().asPromise();
    }

    @Override
    public Promise<ActionResponse, ResourceException> actionInstance(final Context context,
                                                                     final String resourceId,
                                                                     final ActionRequest request) {
        return new NotSupportedException().asPromise();
    }

    /**
     * Gets the UserID from PAT
     *
     * @param context
     * @return UserID from response, Null in case response is invalid
     */
    private String introspectToken(final Context context) {
        Request request = new Request();

        final String pat = OAuth2.getBearerAccessToken(((HttpContext) context.getParent()).getHeaderAsString("Authorization"));
        if (null == pat) {
            return null;
        }

        request.setUri(service.getIntrospectionEndpoint());
        // Should accept a PAT as per the spec (See OPENAM-6320 / OPENAM-5928)
        //request.getHeaders().put("Authorization", format("Bearer %s", pat));
        request.getHeaders().put("Accept", "application/json");

        Form query = new Form();
        query.putSingle("token", pat);
        query.putSingle("client_id", service.getClientId());
        query.putSingle("client_secret", service.getClientSecret());
        query.toRequestEntity(request);

        Promise<org.forgerock.http.protocol.Response, NeverThrowsException> responsePromise = service.getProtectionApiHandler().handle(context, request);
        try {
            org.forgerock.http.protocol.Response response = responsePromise.get();
            if ((Status.OK == response.getStatus()) && null != response.getEntity()) {
                JsonValue value = json(response.getEntity().getJson());

                // Gets the "sub" field from JSON response
                return value.get("sub").asString();
            }
        } catch (IOException | ExecutionException | InterruptedException e) {
            return null;
        }
        return null;
    }
}
