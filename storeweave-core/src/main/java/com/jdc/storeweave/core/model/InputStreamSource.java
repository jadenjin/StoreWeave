package com.jdc.storeweave.core.model;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface InputStreamSource {

    InputStream openStream() throws IOException;
}
