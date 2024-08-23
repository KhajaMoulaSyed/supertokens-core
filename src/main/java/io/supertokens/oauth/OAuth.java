/*
 *    Copyright (c) 2024, VRAI Labs and/or its affiliates. All rights reserved.
 *
 *    This software is licensed under the Apache License, Version 2.0 (the
 *    "License") as published by the Apache Software Foundation.
 *
 *    You may not use this file except in compliance with the License. You may
 *    obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package io.supertokens.oauth;

import com.auth0.jwt.exceptions.JWTCreationException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.supertokens.Main;
import io.supertokens.config.Config;
import io.supertokens.httpRequest.HttpRequest;
import io.supertokens.httpRequest.HttpResponseException;
import io.supertokens.jwt.JWTSigningFunctions;
import io.supertokens.jwt.exceptions.UnsupportedJWTSigningAlgorithmException;
import io.supertokens.oauth.exceptions.*;
import io.supertokens.pluginInterface.Storage;
import io.supertokens.pluginInterface.StorageUtils;
import io.supertokens.pluginInterface.exceptions.InvalidConfigException;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.exceptions.StorageTransactionLogicException;
import io.supertokens.pluginInterface.jwt.JWTSigningKeyInfo;
import io.supertokens.pluginInterface.multitenancy.AppIdentifier;
import io.supertokens.pluginInterface.multitenancy.exceptions.TenantOrAppNotFoundException;
import io.supertokens.pluginInterface.oauth.OAuthStorage;
import io.supertokens.pluginInterface.oauth.exceptions.OAuth2ClientAlreadyExistsForAppException;
import io.supertokens.session.jwt.JWT;
import io.supertokens.session.jwt.JWT.JWTException;
import io.supertokens.signingkeys.JWTSigningKey;
import io.supertokens.signingkeys.SigningKeys;
import io.supertokens.utils.Utils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.*;

public class OAuth {

    private static final String LOCATION_HEADER_NAME = "Location";
    private static final String COOKIES_HEADER_NAME = "Set-Cookie";
    private static final String ERROR_LITERAL = "error=";
    private static final String ERROR_DESCRIPTION_LITERAL = "error_description=";

    private static final String HYDRA_AUTH_ENDPOINT = "/oauth2/auth";
    private static final String HYDRA_TOKEN_ENDPOINT = "/oauth2/token";
    private static final String HYDRA_CLIENTS_ENDPOINT = "/admin/clients";

    public static OAuthAuthResponse getAuthorizationUrl(Main main, AppIdentifier appIdentifier, Storage storage, JsonObject paramsFromSdk)
            throws InvalidConfigException, HttpResponseException, IOException, OAuthAuthException, StorageQueryException,
            TenantOrAppNotFoundException {

        OAuthStorage oauthStorage = StorageUtils.getOAuthStorage(storage);

        String redirectTo = null;
        List<String> cookies = null;

        String publicOAuthProviderServiceUrl = Config.getConfig(appIdentifier.getAsPublicTenantIdentifier(), main).getOAuthProviderPublicServiceUrl();
        String hydraInternalAddress = Config.getConfig(appIdentifier.getAsPublicTenantIdentifier(), main).getOauthProviderUrlConfiguredInHydra();
        String hydraBaseUrlForConsentAndLogin = Config.getConfig(appIdentifier.getAsPublicTenantIdentifier(), main).getOauthProviderConsentLoginBaseUrl();

        String clientId = paramsFromSdk.get("client_id").getAsString();
        String cookie = null;

        if (paramsFromSdk.has("cookie")) {
            cookie = paramsFromSdk.get("cookie").getAsString();
            cookie = cookie.replaceAll("st_oauth_", "ory_hydra_");
            paramsFromSdk.remove("cookie");
        }

        if (!oauthStorage.doesClientIdExistForThisApp(appIdentifier, clientId)) {
            throw new OAuthAuthException("invalid_client", "Client authentication failed (e.g., unknown client, no client authentication included, or unsupported authentication method). The requested OAuth 2.0 Client does not exist.");
        } else {
            // we query hydra
            Map<String, String> queryParamsForHydra = constructHydraRequestParamsForAuthorizationGETAPICall(paramsFromSdk);
            Map<String, String> headers = new HashMap<>();
            Map<String, List<String>> responseHeaders = new HashMap<>();

            if (cookie != null) {
                headers.put("Cookie", cookie);
            }

            HttpRequest.sendGETRequestWithResponseHeaders(main, "", publicOAuthProviderServiceUrl + HYDRA_AUTH_ENDPOINT, queryParamsForHydra, headers, 10000, 10000, null, responseHeaders, false);

            if (!responseHeaders.isEmpty() && responseHeaders.containsKey(LOCATION_HEADER_NAME)) {
                String locationHeaderValue = responseHeaders.get(LOCATION_HEADER_NAME).get(0);
                if(Utils.containsUrl(locationHeaderValue, hydraInternalAddress, true)){
                    String error = getValueOfQueryParam(locationHeaderValue, ERROR_LITERAL);
                    String errorDescription = getValueOfQueryParam(locationHeaderValue, ERROR_DESCRIPTION_LITERAL);
                    throw new OAuthAuthException(error, errorDescription);
                }

                if(Utils.containsUrl(locationHeaderValue, hydraBaseUrlForConsentAndLogin, true)){
                    redirectTo = locationHeaderValue.replace(hydraBaseUrlForConsentAndLogin, "{apiDomain}");
                } else {
                    redirectTo = locationHeaderValue;
                }
                if (redirectTo.contains("code=ory_ac_")) {
                    redirectTo = redirectTo.replace("code=ory_ac_", "code=st_ac_");
                }
            } else {
                throw new RuntimeException("Unexpected answer from Oauth Provider");
            }
            if(responseHeaders.containsKey(COOKIES_HEADER_NAME)){
                cookies = new ArrayList<>(responseHeaders.get(COOKIES_HEADER_NAME));

                for (int i = 0; i < cookies.size(); i++) {
                    String cookieStr = cookies.get(i);
                    if (cookieStr.startsWith("ory_hydra_")) {
                        cookies.set(i, "st_oauth_" + cookieStr.substring(10));
                    }
                }
            }
        }

        return new OAuthAuthResponse(redirectTo, cookies);
    }

    public static JsonObject getToken(Main main, AppIdentifier appIdentifier, Storage storage, JsonObject bodyFromSdk, boolean useDynamicKey) throws InvalidConfigException, TenantOrAppNotFoundException, OAuthAuthException, StorageQueryException {
        OAuthStorage oauthStorage = StorageUtils.getOAuthStorage(storage);
        String clientId = bodyFromSdk.get("client_id").getAsString();

        if (!oauthStorage.doesClientIdExistForThisApp(appIdentifier, clientId)) {
            throw new OAuthAuthException("invalid_client", "Client authentication failed (e.g., unknown client, no client authentication included, or unsupported authentication method). The requested OAuth 2.0 Client does not exist.");
        }

        String publicOAuthProviderServiceUrl = Config.getConfig(appIdentifier.getAsPublicTenantIdentifier(), main).getOAuthProviderPublicServiceUrl();
        try {
            Map<String, String> bodyParams = constructHydraRequestParamsForAuthorizationGETAPICall(bodyFromSdk);
            bodyParams.put("code", bodyParams.get("code").replace("st_ac_", "ory_ac_"));
            JsonObject response = HttpRequest.sendFormPOSTRequest(main, "", publicOAuthProviderServiceUrl + HYDRA_TOKEN_ENDPOINT, bodyParams, 10000, 10000, null);

            // token transformations
            if (response.has("access_token")) {
                String accessToken = response.get("access_token").getAsString();
                accessToken = resignToken(appIdentifier, main, accessToken, 1, useDynamicKey);
                response.addProperty("access_token", accessToken);
            }

            if (response.has("id_token")) {
                String idToken = response.get("id_token").getAsString();
                idToken = resignToken(appIdentifier, main, idToken, 2, useDynamicKey);
                response.addProperty("id_token", idToken);
            }
            // TODO: token transformations
            // TODO: error handling
            return response;
        } catch (HttpResponseException | IOException e) {
            // TODO Auto-generated catch block
            throw new RuntimeException(e);
        } catch (InvalidKeyException e) {
            // TODO Auto-generated catch block
            throw new RuntimeException(e);
        } catch (NoSuchAlgorithmException e) {
            // TODO Auto-generated catch block
            throw new RuntimeException(e);
        } catch (JWTException e) {
            // TODO Auto-generated catch block
            throw new RuntimeException(e);
        } catch (InvalidKeySpecException e) {
            // TODO Auto-generated catch block
            throw new RuntimeException(e);
        } catch (JWTCreationException e) {
            // TODO Auto-generated catch block
            throw new RuntimeException(e);
        } catch (StorageTransactionLogicException e) {
            // TODO Auto-generated catch block
            throw new RuntimeException(e);
        } catch (UnsupportedJWTSigningAlgorithmException e) {
            // TODO Auto-generated catch block
            throw new RuntimeException(e);
        }
    }

    private static String resignToken(AppIdentifier appIdentifier, Main main, String token, int stt, boolean useDynamicSigningKey) throws IOException, HttpResponseException, JWTException, InvalidKeyException, NoSuchAlgorithmException, StorageQueryException, StorageTransactionLogicException, UnsupportedJWTSigningAlgorithmException, TenantOrAppNotFoundException, InvalidKeySpecException, JWTCreationException {
        // Load the JWKS from the specified URL
        String jwksUrl = "http://localhost:4444/.well-known/jwks.json";
        JsonObject jwksResponse = HttpRequest.sendGETRequest(main, "", jwksUrl, null, 10000, 10000, null);
        JsonArray keys = jwksResponse.get("keys").getAsJsonArray();

        // Validate the JWT and extract claims using the fetched public signing keys
        JWT.JWTPreParseInfo jwtInfo = JWT.preParseJWTInfo(token);
        JWT.JWTInfo jwtResult = null;
        for (JsonElement key : keys) {
            JsonObject keyObject = key.getAsJsonObject();
            String kid = keyObject.get("kid").getAsString();
            if (jwtInfo.kid.equals(kid)) {
                jwtResult = JWT.verifyJWTAndGetPayload(jwtInfo, keyObject.get("n").getAsString(), keyObject.get("e").getAsString());
                break;
            }
        }
        if (jwtResult == null) {
            throw new RuntimeException("No matching key found for JWT verification");
        }
        JsonObject payload = jwtResult.payload;
        // move keys in ext to root
        if (payload.has("ext")) {
            JsonObject ext = payload.getAsJsonObject("ext");
            for (Map.Entry<String, JsonElement> entry : ext.entrySet()) {
                payload.add(entry.getKey(), entry.getValue());
            }
            payload.remove("ext");
        }
        payload.addProperty("stt", stt);

        JWTSigningKeyInfo keyToUse;
        if (useDynamicSigningKey) {
            keyToUse = Utils.getJWTSigningKeyInfoFromKeyInfo(
                    SigningKeys.getInstance(appIdentifier, main).getLatestIssuedDynamicKey());
        } else {
            keyToUse = SigningKeys.getInstance(appIdentifier, main)
                    .getStaticKeyForAlgorithm(JWTSigningKey.SupportedAlgorithms.RS256);
        }

        token = JWTSigningFunctions.createJWTToken(JWTSigningKey.SupportedAlgorithms.RS256, new HashMap<>(),
                    payload, null, payload.get("exp").getAsLong(), payload.get("iat").getAsLong(), keyToUse);
        return token;
    }

    //This more or less acts as a pass-through for the sdks, apart from camelCase <-> snake_case key transformation and setting a few default values
    public static JsonObject registerOAuthClient(Main main, AppIdentifier appIdentifier, Storage storage, JsonObject paramsFromSdk)
            throws TenantOrAppNotFoundException, InvalidConfigException, IOException,
            OAuthAPIInvalidInputException,
            NoSuchAlgorithmException, StorageQueryException {

        OAuthStorage oauthStorage = StorageUtils.getOAuthStorage(storage);
        String adminOAuthProviderServiceUrl = Config.getConfig(appIdentifier.getAsPublicTenantIdentifier(), main).getOAuthProviderAdminServiceUrl();

        byte[] idBaseBytes = new byte[48];

        while(true){
            new SecureRandom().nextBytes(idBaseBytes);
            String clientId = "supertokens_" + Utils.hashSHA256Base64UrlSafe(idBaseBytes);
            try {

                JsonObject hydraRequestBody = constructHydraRequestParamsForRegisterClientPOST(paramsFromSdk, clientId);
                JsonObject hydraResponse = HttpRequest.sendJsonPOSTRequest(main, "", adminOAuthProviderServiceUrl + HYDRA_CLIENTS_ENDPOINT, hydraRequestBody, 10000, 10000, null);

                oauthStorage.addClientForApp(appIdentifier, clientId);

                return formatResponseForSDK(hydraResponse); //sdk expects everything from hydra in camelCase
            } catch (HttpResponseException e) {
                try {
                    if (e.statusCode == 409){
                        //no-op
                        //client with id already exists, silently retry with different Id
                    } else {
                        //other error from hydra, like invalid content in json. Throw exception
                        throw createCustomExceptionFromHttpResponseException(
                                e, OAuthAPIInvalidInputException.class);
                    }
                } catch (NoSuchMethodException | InvocationTargetException | InstantiationException |
                         IllegalAccessException ex) {
                    throw new RuntimeException(ex);
                }
            } catch (OAuth2ClientAlreadyExistsForAppException e) {
                //in theory, this is unreachable. We are registering new clients here, so this should not happen.
                throw new RuntimeException(e);
            }
        }
    }

    public static JsonObject loadOAuthClient(Main main, AppIdentifier appIdentifier, Storage storage, String clientId)
            throws TenantOrAppNotFoundException, InvalidConfigException, StorageQueryException,
            IOException, OAuthClientNotFoundException {
        OAuthStorage oauthStorage = StorageUtils.getOAuthStorage(storage);

        String adminOAuthProviderServiceUrl = Config.getConfig(appIdentifier.getAsPublicTenantIdentifier(), main).getOAuthProviderAdminServiceUrl();

        if (!oauthStorage.doesClientIdExistForThisApp(appIdentifier, clientId)) {
            throw new OAuthClientNotFoundException("Unable to locate the resource", "");
        } else {
            try {
                JsonObject hydraResponse = HttpRequest.sendGETRequest(main, "", adminOAuthProviderServiceUrl + HYDRA_CLIENTS_ENDPOINT + "/" + clientId, null, 10000, 10000, null);
                return  formatResponseForSDK(hydraResponse);
            } catch (HttpResponseException e) {
                try {
                    throw createCustomExceptionFromHttpResponseException(e, OAuthClientNotFoundException.class);
                } catch (NoSuchMethodException | InvocationTargetException | InstantiationException |
                         IllegalAccessException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    public static void deleteOAuthClient(Main main, AppIdentifier appIdentifier, Storage storage, String clientId)
            throws TenantOrAppNotFoundException, InvalidConfigException, StorageQueryException,
            IOException, OAuthClientNotFoundException {
        OAuthStorage oauthStorage = StorageUtils.getOAuthStorage(storage);

        String adminOAuthProviderServiceUrl = Config.getConfig(appIdentifier.getAsPublicTenantIdentifier(), main).getOAuthProviderAdminServiceUrl();

        if (!oauthStorage.doesClientIdExistForThisApp(appIdentifier, clientId)) {
            throw new OAuthClientNotFoundException("Unable to locate the resource", "");
        } else {
            try {
                oauthStorage.removeAppClientAssociation(appIdentifier, clientId);
                HttpRequest.sendJsonDELETERequest(main, "", adminOAuthProviderServiceUrl + HYDRA_CLIENTS_ENDPOINT + "/" + clientId, null, 10000, 10000, null);
            } catch (HttpResponseException e) {
                try {
                    throw createCustomExceptionFromHttpResponseException(e, OAuthClientNotFoundException.class);
                } catch (NoSuchMethodException | InvocationTargetException | InstantiationException |
                         IllegalAccessException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    public static JsonObject updateOauthClient(Main main, AppIdentifier appIdentifier, Storage storage, JsonObject paramsFromSdk)
            throws TenantOrAppNotFoundException, InvalidConfigException, StorageQueryException,
            InvocationTargetException, NoSuchMethodException, InstantiationException,
            IllegalAccessException, OAuthClientNotFoundException, OAuthAPIInvalidInputException,
            OAuthClientUpdateException {

        OAuthStorage oauthStorage = StorageUtils.getOAuthStorage(storage);
        String adminOAuthProviderServiceUrl = Config.getConfig(appIdentifier.getAsPublicTenantIdentifier(), main).getOAuthProviderAdminServiceUrl();

        String clientId = paramsFromSdk.get("clientId").getAsString();

        if (!oauthStorage.doesClientIdExistForThisApp(appIdentifier, clientId)) {
            throw new OAuthClientNotFoundException("Unable to locate the resource", "");
        } else {
            JsonArray hydraInput = translateIncomingDataToHydraUpdateFormat(paramsFromSdk);
            try {
                JsonObject updatedClient = HttpRequest.sendJsonPATCHRequest(main, adminOAuthProviderServiceUrl + HYDRA_CLIENTS_ENDPOINT+ "/" + clientId, hydraInput);
                return formatResponseForSDK(updatedClient);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            } catch (HttpResponseException e) {
                int responseStatusCode = e.statusCode;
                switch (responseStatusCode){
                    case 400 -> throw createCustomExceptionFromHttpResponseException(e, OAuthAPIInvalidInputException.class);
                    case 404 -> throw createCustomExceptionFromHttpResponseException(e, OAuthClientNotFoundException.class);
                    case 500 -> throw createCustomExceptionFromHttpResponseException(e, OAuthClientUpdateException.class); // hydra is not so helpful with the error messages at this endpoint..
                    default -> throw new RuntimeException(e);
                }
            }
        }
    }

    private static JsonArray translateIncomingDataToHydraUpdateFormat(JsonObject input){
        JsonArray hydraPatchFormat = new JsonArray();
        for (Map.Entry<String, JsonElement> changeIt : input.entrySet()) {
            if (changeIt.getKey().equals("clientId")) {
                continue; // we are not updating clientIds!
            }
            hydraPatchFormat.add(translateToHydraPatch(changeIt.getKey(),changeIt.getValue()));
        }

        return hydraPatchFormat;
    }

    private static JsonObject translateToHydraPatch(String elementName, JsonElement newValue){
        JsonObject patchFormat = new JsonObject();
        String hydraElementName = Utils.camelCaseToSnakeCase(elementName);
        patchFormat.addProperty("from", "/" + hydraElementName);
        patchFormat.addProperty("path", "/" + hydraElementName);
        patchFormat.addProperty("op", "replace"); // What was sent by the sdk should be handled as a complete new value for the property
        patchFormat.add("value", newValue);

        return patchFormat;
    }

    private static <T extends OAuthException> T createCustomExceptionFromHttpResponseException(HttpResponseException exception, Class<T> customExceptionClass)
            throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        String errorMessage = exception.rawMessage;
        JsonObject errorResponse = (JsonObject) new JsonParser().parse(errorMessage);
        String error = errorResponse.get("error").getAsString();
        String errorDescription = errorResponse.get("error_description").getAsString();
        return customExceptionClass.getDeclaredConstructor(String.class, String.class).newInstance(error, errorDescription);
    }

    private static JsonObject constructHydraRequestParamsForRegisterClientPOST(JsonObject paramsFromSdk, String generatedClientId){
        JsonObject requestBody = new JsonObject();

        //translating camelCase keys to snakeCase keys
        for (Map.Entry<String, JsonElement> jsonEntry : paramsFromSdk.entrySet()){
            requestBody.add(Utils.camelCaseToSnakeCase(jsonEntry.getKey()), jsonEntry.getValue());
        }

        //add client_id
        requestBody.addProperty("client_id", generatedClientId);

        //setting other non-changing defaults
        requestBody.addProperty("access_token_strategy", "jwt");
        requestBody.addProperty("skip_consent", true);
        requestBody.addProperty("subject_type", "public");

        return requestBody;
    }

    private static JsonObject formatResponseForSDK(JsonObject response) {
        JsonObject formattedResponse = new JsonObject();

        //translating snake_case keys to camelCase keys
        for (Map.Entry<String, JsonElement> jsonEntry : response.entrySet()){
            formattedResponse.add(Utils.snakeCaseToCamelCase(jsonEntry.getKey()), jsonEntry.getValue());
        }

        return formattedResponse;
    }

    private static Map<String, String> constructHydraRequestParamsForAuthorizationGETAPICall(JsonObject inputFromSdk) {
        Map<String, String> queryParamsForHydra = new HashMap<>();
        for(Map.Entry<String, JsonElement> jsonElement : inputFromSdk.entrySet()){
            queryParamsForHydra.put(jsonElement.getKey(), jsonElement.getValue().getAsString());
        }
        return  queryParamsForHydra;
    }

    private static String getValueOfQueryParam(String url, String queryParam){
        String valueOfQueryParam = "";
        if(!queryParam.endsWith("=")){
            queryParam = queryParam + "=";
        }
        int startIndex = url.indexOf(queryParam) + queryParam.length(); // start after the '=' sign
        int endIndex = url.indexOf("&", startIndex);
        if (endIndex == -1){
            endIndex = url.length();
        }
        valueOfQueryParam = url.substring(startIndex, endIndex); // substring the url from the '=' to the next '&' or to the end of the url if there are no more &s
        return URLDecoder.decode(valueOfQueryParam, StandardCharsets.UTF_8);
    }
;}
