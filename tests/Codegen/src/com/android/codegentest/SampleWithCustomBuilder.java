/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.codegentest;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;

import com.android.internal.util.DataClass;

import java.util.concurrent.TimeUnit;

@DataClass(genBuilder = true, genAidl = false, genToString = true)
public class SampleWithCustomBuilder implements Parcelable {

    long delayAmount = 0;
    @NonNull
    TimeUnit delayUnit = TimeUnit.MILLISECONDS;

    long creationTimestamp = SystemClock.uptimeMillis();

    /**
     * You can declare a class named {@code BaseBuilder} to have the generated builder extend from
     * it instead.
     *
     * Same rules apply where defining a non-abstract method will suppress the generation of a
     * method with the same signature.
     *
     * For abstract generatable methods, implementations are generated as normal, but original
     * visibility is used, allowing you to hide methods.
     *
     * Here for example, we hide {@link #setDelayUnit} and {@link #setDelayAmount} from public API,
     * replacing it with {@link #setDelay} instead.
     */
    // Suppress setter generation for a field that is not supposed to come from user input.
    @DataClass.Suppress("setCreationTimestamp")
    static abstract class BaseBuilder {

        /**
         * Hide methods by declaring them with reduced (package-private) visibility.
         */
        abstract Builder setDelayAmount(long value);

        /**
         * Alternatively, hide methods by using @hide, to hide them from public API only.
         *
         * @hide
         */
        public abstract Builder setDelayUnit(TimeUnit value);

        /**
         * Can provide additional method on the builder, e.g. as a replacement for the ones we've
         * just hidden.
         */
        public Builder setDelay(long amount, TimeUnit unit) {
            setDelayAmount(amount);
            setDelayUnit(unit);
            return (Builder) this;
        }
    }


    private static TimeUnit unparcelDelayUnit(Parcel p) {
        return TimeUnit.values()[p.readInt()];
    }

    private void parcelDelayUnit(Parcel p, int flags) {
        p.writeInt(delayUnit.ordinal());
    }



    // Code below generated by codegen v1.0.7.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/tests/Codegen/src/com/android/codegentest/SampleWithCustomBuilder.java


    @DataClass.Generated.Member
    /* package-private */ SampleWithCustomBuilder(
            long delayAmount,
            @NonNull TimeUnit delayUnit,
            long creationTimestamp) {
        this.delayAmount = delayAmount;
        this.delayUnit = delayUnit;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, delayUnit);
        this.creationTimestamp = creationTimestamp;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public long getDelayAmount() {
        return delayAmount;
    }

    @DataClass.Generated.Member
    public @NonNull TimeUnit getDelayUnit() {
        return delayUnit;
    }

    @DataClass.Generated.Member
    public long getCreationTimestamp() {
        return creationTimestamp;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "SampleWithCustomBuilder { " +
                "delayAmount = " + delayAmount + ", " +
                "delayUnit = " + delayUnit + ", " +
                "creationTimestamp = " + creationTimestamp +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeLong(delayAmount);
        parcelDelayUnit(dest, flags);
        dest.writeLong(creationTimestamp);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    protected SampleWithCustomBuilder(Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        long _delayAmount = in.readLong();
        TimeUnit _delayUnit = unparcelDelayUnit(in);
        long _creationTimestamp = in.readLong();

        this.delayAmount = _delayAmount;
        this.delayUnit = _delayUnit;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, delayUnit);
        this.creationTimestamp = _creationTimestamp;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<SampleWithCustomBuilder> CREATOR
            = new Parcelable.Creator<SampleWithCustomBuilder>() {
        @Override
        public SampleWithCustomBuilder[] newArray(int size) {
            return new SampleWithCustomBuilder[size];
        }

        @Override
        public SampleWithCustomBuilder createFromParcel(Parcel in) {
            return new SampleWithCustomBuilder(in);
        }
    };

    /**
     * A builder for {@link SampleWithCustomBuilder}
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static class Builder extends BaseBuilder {

        private long delayAmount;
        private @NonNull TimeUnit delayUnit;
        private long creationTimestamp;

        private long mBuilderFieldsSet = 0L;

        public Builder() {
        }

        @DataClass.Generated.Member
        @Override
        @NonNull Builder setDelayAmount(long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            delayAmount = value;
            return this;
        }

        @DataClass.Generated.Member
        @Override
        public @NonNull Builder setDelayUnit(@NonNull TimeUnit value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            delayUnit = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public SampleWithCustomBuilder build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8; // Mark builder used

            if ((mBuilderFieldsSet & 0x1) == 0) {
                delayAmount = 0;
            }
            if ((mBuilderFieldsSet & 0x2) == 0) {
                delayUnit = TimeUnit.MILLISECONDS;
            }
            if ((mBuilderFieldsSet & 0x4) == 0) {
                creationTimestamp = SystemClock.uptimeMillis();
            }
            SampleWithCustomBuilder o = new SampleWithCustomBuilder(
                    delayAmount,
                    delayUnit,
                    creationTimestamp);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x8) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1570576453295L,
            codegenVersion = "1.0.7",
            sourceFile = "frameworks/base/tests/Codegen/src/com/android/codegentest/SampleWithCustomBuilder.java",
            inputSignatures = "  long delayAmount\n @android.annotation.NonNull java.util.concurrent.TimeUnit delayUnit\n  long creationTimestamp\nprivate static  java.util.concurrent.TimeUnit unparcelDelayUnit(android.os.Parcel)\nprivate  void parcelDelayUnit(android.os.Parcel,int)\nclass SampleWithCustomBuilder extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genBuilder=true, genAidl=false, genToString=true)\nabstract  com.android.codegentest.SampleWithCustomBuilder.Builder setDelayAmount(long)\npublic abstract  com.android.codegentest.SampleWithCustomBuilder.Builder setDelayUnit(java.util.concurrent.TimeUnit)\npublic  com.android.codegentest.SampleWithCustomBuilder.Builder setDelay(long,java.util.concurrent.TimeUnit)\nclass BaseBuilder extends java.lang.Object implements []")
    @Deprecated
    private void __metadata() {}

}
