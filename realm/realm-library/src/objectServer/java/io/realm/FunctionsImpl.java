/*
 * Copyright 2020 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.realm;

import org.bson.BSONException;
import org.bson.BsonElement;
import org.bson.codecs.Decoder;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.bson.codecs.configuration.CodecRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.realm.internal.Util;
import io.realm.internal.jni.JniBsonProtocol;
import io.realm.internal.jni.OsJNIResultCallback;
import io.realm.internal.network.ResultHandler;
import io.realm.internal.objectstore.OsJavaNetworkTransport;
import io.realm.mongodb.functions.Functions;

/**
 * Internal implementation of Functions invoking the actual OS function in the context of the
 * {@link RealmUser}/{@link RealmApp}.
 */
class FunctionsImpl extends Functions {

    FunctionsImpl(RealmUser user) {
        this(user, user.getApp().getConfiguration().getDefaultCodecRegistry());
    }

    FunctionsImpl(RealmUser user, CodecRegistry codecRegistry) {
        super(user, codecRegistry);
    }

    // Invokes actual MongoDB Realm Function in the context of the associated user/app.
    @Override
    public <T> T invoke(String name, List<?> args, CodecRegistry codecRegistry, Decoder<T> resultDecoder) {
        Util.checkEmpty(name, "name");

        String encodedArgs;
        try {
            encodedArgs = JniBsonProtocol.encode(args, codecRegistry);
        } catch (CodecConfigurationException e) {
            throw new ObjectServerError(ErrorCode.BSON_CODEC_NOT_FOUND, "Could not resolve encoder for arguments", e);
        } catch (Exception e) {
            throw new ObjectServerError(ErrorCode.BSON_ENCODING, "Error encoding function arguments", e);
        }

        // NativePO calling scheme is actually synchronous
        AtomicReference<String> success = new AtomicReference<>(null);
        AtomicReference<ObjectServerError> error = new AtomicReference<>(null);
        OsJNIResultCallback<String> callback = new OsJNIResultCallback<String>(success, error) {
            @Override
            protected String mapSuccess(Object result) {
                return (String) result;
            }
        };
        nativeCallFunction(user.getApp().nativePtr, user.osUser.getNativePtr(), name, encodedArgs, callback);
        String encodedResponse = ResultHandler.handleResult(success, error);
        T result;
        try {
            result = JniBsonProtocol.decode(encodedResponse, resultDecoder);
        } catch (Exception e) {
            throw new ObjectServerError(ErrorCode.BSON_DECODING, "Error decoding function result", e);
        }
        return result;
    }

   private static native void nativeCallFunction(long nativeAppPtr, long nativeUserPtr, String name, String args_json, OsJavaNetworkTransport.NetworkTransportJNIResultCallback callback);

}
