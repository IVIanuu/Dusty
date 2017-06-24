/*
 * Copyright 2017 Manuel Wrage
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

package com.ivianuu.dusty;

import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.util.TimeUtils;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author Manuel Wrage (IVIanuu)
 */

public class Dusty {

    private static final Map<Class<?>, Constructor> CLEARINGS = new LinkedHashMap<>();

    /**
     * Registers the fragment and clears all annotated values in on destroy view
     */
    public static void register(final Fragment target) {
        final Constructor constructor = findClearingConstructorForClass(target.getClass());
        if (constructor != null) {
            final FragmentManager fragmentManager = target.getFragmentManager();

            fragmentManager.registerFragmentLifecycleCallbacks(
                    new FragmentManager.FragmentLifecycleCallbacks() {
                        @Override
                        public void onFragmentViewDestroyed(FragmentManager fm, Fragment f) {
                            // check target
                            if (f == target) {
                                dust(target);

                                // unregister lifecycle callback
                                fragmentManager.unregisterFragmentLifecycleCallbacks(this);
                            }
                        }
                    }, false);
        }
    }

    /**
     * Clears all annotated values
     */
    public static void dust(final Fragment target) {
        final Constructor constructor = findClearingConstructorForClass(target.getClass());
        if (constructor != null) {
            try {
                // clear target
                constructor.newInstance(target);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Nullable
    private static Constructor findClearingConstructorForClass(Class<?> cls) {
        Constructor clearingConstructor = CLEARINGS.get(cls);
        if (clearingConstructor != null) {
            return clearingConstructor;
        }
        String clsName = cls.getName();
        if (clsName.startsWith("android.") || clsName.startsWith("java.")) {
            return null;
        }
        try {
            Class<?> bindingClass = cls.getClassLoader().loadClass(clsName + "_AutoClearing");
            //noinspection unchecked
            clearingConstructor =  bindingClass.getConstructor(cls);
        } catch (ClassNotFoundException e) {
            clearingConstructor = findClearingConstructorForClass(cls.getSuperclass());
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Unable to find binding constructor for " + clsName, e);
        }

        CLEARINGS.put(cls, clearingConstructor);
        return clearingConstructor;
    }
}
