package picard.http;

import java.nio.ByteBuffer;

public final class MultipartParser {

    static final byte CR        = (byte) 0x0D;
    static final byte LF        = (byte) 0x0A;
    static final byte DASH      = (byte) 0x2D;
    static final byte [] PREFIX = new byte [] { CR, LF, DASH, DASH };

    %%{
        machine multipart;

        action peek_delimiter {
            // System.out.println("#peek_delimiter: BEGIN - " + fpc);

            // If the current character is CR, then we should start
            // attempting to match the delimiter.
            if (CR == fc) {
                bodyEnd = fpc;

                // Start at 1 since the first character has already
                // been matched.
                int curr  = 0;
                int limit = Math.min(delimiter.limit(), buf.limit() - fpc);

                peek_delimiter: {
                    // System.out.println("Starting loop: " + fpc + " - " + limit + " - " + curr);

                    while (++curr < limit) {
                        if (delimiter.get(curr) != buf.get(fpc + curr)) {
                            // System.out.println("FAIL: " + ( fpc + curr ));
                            fexec fpc + curr;
                            break peek_delimiter;
                        }
                    }

                    // System.out.println("MATCHED: " + ( fpc + curr));

                    // The delimiter has been matched
                    if (curr == delimiter.limit()) {
                        // System.out.println("-- Full delimiter match");

                        if (parsingBody) {

                            // If the body start pointer is greater
                            // than zero, then the entire body is in
                            // the current chunk.
                            if (headers != null) {
                                callback.part(headers, slice(buf, bodyStart, bodyEnd));
                                headers = null;
                            }
                            else {
                                if (bodyEnd > bodyStart) {
                                    // System.out.println("FINAL CHUNK: " + buf + " " + bodyStart +
                                    //                    " - " + bodyEnd);
                                    callback.chunk(slice(buf, bodyStart, bodyEnd));
                                }

                                callback.chunk(null);
                            }

                            bodyStart = 0;
                            bodyEnd   = 0;
                        }

                        fnext head;
                        fexec fpc + curr;
                    }
                    // The end of the current buffer has been reached
                    else {
                        // System.out.println("-- Partial delimiter match");
                        // System.out.println("-- start: " + bodyStart + ", end: " + bodyEnd);

                        if (parsingBody) {

                            if (headers != null) {
                                callback.part(headers, null);
                                headers = null;
                            }

                            if (bodyEnd > bodyStart) {
                                callback.chunk(slice(buf, bodyStart, bodyEnd));
                            }

                            bodyStart = 0;
                            bodyEnd   = 0;
                        }

                        delimiterPos = curr;

                        // System.out.println("#peek_delimiter: fgoto delimiter");
                        fnext delimiter;

                        // System.out.println("#peek_delimiter: return");
                        return;
                    }
                }
            }
            else {
                bodyEnd = fpc + 1;
            }
        }

        action parse_delimiter {
            int curr  = 0;
            int limit = Math.min(delimiter.limit() - delimiterPos, buf.limit());

            // System.out.println("#parse_delimiter: BEGIN - " + fpc + ", " + delimiterPos);

            parse_delimiter: {
                while (curr < limit) {
                    if (delimiter.get(curr + delimiterPos) != buf.get(curr)) {
                        // System.out.println("#parse_delimiter: FAIL - " + (fpc + curr));

                        if (parsingBody) {
                            // System.out.println(delimiterPos + " + " + curr + ", " + fpc);
                            callback.chunk(slice(delimiter, 0, delimiterPos + curr));

                            bodyStart = fpc + curr;

                            fexec fpc + curr;
                            fnext body;
                        }
                        else {
                            fexec fpc + curr;
                            fnext start;
                        }

                        break parse_delimiter;
                    }

                    ++curr;
                }

                // The delimiter has been matched
                if (curr == delimiter.limit() - delimiterPos) {
                    // System.out.println("#parse_delimiter: MATCH FULL - " + fpc);

                    if (parsingBody) {
                        callback.chunk(null);
                    }

                    fnext head;
                    fexec fpc + curr;
                }
                // The end fo the current buffer has been reached
                else {
                    // System.out.println("#parse_delimiter: MATCH PARTIAL - " + fpc);
                    delimiterPos += curr;
                    return;
                }
            }
        }

        action start_head {
            parsingBody = true;
            // System.out.println("Starting headers: " + fpc);

            headers = callback.blankHeaders();
            bodyStart = 0;
            bodyEnd   = 0;
        }

        action end_head {
            // System.out.println("Ending headers: " + fpc);
            bodyStart  = fpc;
        }

        action end_parts {
            // System.out.println("~~~~ ALL DONE");
            callback.done();
            fgoto epilogue;
        }

        action something_went_wrong {
            if (true) {
                throw new HttpParserException("Something went wrong: " + fpc);
            }
        }

        include "multipart.rl";
    }%%

    private int cs;
    private int delimiterPos;

    private boolean parsingBody;

    private Object       headers;
    private String       headerName;
    private ChunkedValue headerNameChunks;
    private HeaderValue  headerValue;

    private final ByteBuffer delimiter;

    private final MultipartParserCallback callback;

    %% write data;

    public MultipartParser(ByteBuffer boundary, MultipartParserCallback callback) {
        %% write init;

        ByteBuffer delimiter = ByteBuffer.allocate(4 + boundary.remaining());

        delimiter.put(PREFIX);
        delimiter.put(boundary);
        delimiter.flip();

        this.delimiter = delimiter;
        this.callback  = callback;
    }

    public void execute(ByteBuffer buf) {
        int bodyStart = 0;
        int bodyEnd   = 0;

        // Setup ragel variables
        int p   = buf.position();
        int pe  = buf.limit();
        int eof = pe + 1;

        bridge(buf, headerNameChunks);
        bridge(buf, headerValue);

        // System.out.println("================== '" +
        //                    new String(buf.array()).
        //                    replaceAll("\n", "\\\\n").
        //                    replaceAll("\r", "\\\\r")
        //                    + "'");

        %% getkey buf.get(p);
        %% write exec;

        // System.out.println("~~~ DONE WITH PARSING LOOP ~~~");
        // System.out.println("start: " + bodyStart + ", end: " + bodyEnd);
        // System.out.println(headers == null);

        if (parsingBody && bodyEnd > bodyStart) {
            // System.out.println("FLUSHING CHUNK: " + bodyStart + " - " + buf.limit());

            if (headers != null) {
                callback.part(headers, null);
                headers = null;
            }

            callback.chunk(slice(buf, bodyStart, buf.limit()));
        }
    }

    private void bridge(ByteBuffer buf, ChunkedValue chunk) {
        if (chunk != null) {
            chunk.bridge(buf);
        }
    }

    private ByteBuffer slice(ByteBuffer buf, int from, int to) {
        ByteBuffer chunk = buf.asReadOnlyBuffer();

        chunk.position(from);
        chunk.limit(to);

        return chunk;
    }
}
