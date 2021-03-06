/*
 * Copyright (c) 2015, University of Oslo
 *
 * All rights reserved.
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.dhis2.mobile.sdk.network.tasks;

import android.net.Uri;

import org.dhis2.mobile.sdk.entities.CategoryCombo;
import org.dhis2.mobile.sdk.network.APIException;
import org.dhis2.mobile.sdk.network.http.ApiRequest;
import org.dhis2.mobile.sdk.network.http.Request;
import org.dhis2.mobile.sdk.network.http.RequestBuilder;
import org.dhis2.mobile.sdk.network.managers.NetworkManager;
import org.dhis2.mobile.sdk.network.models.Credentials;

import java.util.List;

public final class GetCategoryCombosTask implements ITask<List<CategoryCombo>> {
    private final ApiRequest<String, List<CategoryCombo>> mRequest;

    public GetCategoryCombosTask(NetworkManager manager,
                                 Uri serverUri, Credentials credentials,
                                 List<String> ids, boolean flat) {
        String base64Credentials = manager.getBase64Manager()
                .toBase64(credentials);
        String url = buildQuery(serverUri, ids, flat);
        Request request = RequestBuilder.forUri(url)
                .header("Authorization", base64Credentials)
                .header("Accept", "application/json")
                .build();
        mRequest = new ApiRequest<>(
                request, manager.getHttpManager(), manager.getLogManager(),
                manager.getJsonManager().getCategoryCombosConverter()
        );
    }

    private static String buildQuery(Uri serverUri, List<String> ids, boolean flat) {
        Uri.Builder builder = serverUri.buildUpon()
                .appendEncodedPath("api/categoryCombos/")
                .appendQueryParameter("paging", "false");
        String baseIdentityParams = "id,created,lastUpdated,name,displayName";
        String fields = baseIdentityParams;

        if (!flat) {
            fields += "," + "categories" + "[" + baseIdentityParams + "]";
        }

        builder.appendQueryParameter("fields", fields);
        if (ids != null && ids.size() > 0) {
            for (String id : ids) {
                builder.appendQueryParameter("filter", "id:eq:" + id);
            }
        }
        return builder.build().toString();
    }

    @Override
    public List<CategoryCombo> run() throws APIException {
        return mRequest.request();
    }
}
