package com.stocka.backend.modules.mcp.tool.support;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.springframework.web.multipart.MultipartFile;

/**
 * In-memory {@link MultipartFile} so tools can feed downloaded bytes to the attachment service,
 * which speaks multipart because the REST API does.
 */
public final class BytesMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] bytes;

    public BytesMultipartFile(String name, String originalFilename, String contentType, byte[] bytes) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.bytes = bytes == null ? new byte[0] : bytes;
    }

    @Override
    public String getName() { return name; }

    @Override
    public String getOriginalFilename() { return originalFilename; }

    @Override
    public String getContentType() { return contentType; }

    @Override
    public boolean isEmpty() { return bytes.length == 0; }

    @Override
    public long getSize() { return bytes.length; }

    @Override
    public byte[] getBytes() { return bytes.clone(); }

    @Override
    public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }

    @Override
    public void transferTo(File dest) throws IOException {
        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(bytes);
        }
    }
}
