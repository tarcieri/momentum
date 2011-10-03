package picard.http;

import java.nio.ByteBuffer;

public final class MultipartParser {
    %%{
        machine multipart;

        action start_delimiter {
            System.out.println("#start_delimiter - " + fpc);
            boundaryPos = 0;
            bodyEnd = fpc;
        }

        action parsing_boundary {
            boundaryPos < boundary.limit()
        }

        action parse_boundary {
            System.out.println("Parsing boundary");
            if (fc == boundary.get(boundaryPos)) {
                ++boundaryPos;
            }
            else {
                System.out.println("Parsing boundary failed - " + fpc);
                fhold;

                if (headers == null) {
                    fgoto start;
                }
                else {
                    fgoto body;
                }
            }
        }

        action start_head {
            System.out.println("Starting headers: " + fpc);
            headers = callback.blankHeaders();
        }

        action end_head {
            System.out.println("Ending headers: " + fpc);
            bodyStart = fpc;
        }

        action start_body {
            System.out.println("!!! START BODY - " + fpc);
            // bodyStart = fpc;
        }

        action end_body {
            System.out.println("!!! END BODY - " + fpc);
        }

        action end_part {
            System.out.println("!!! BAM: " + bodyStart + " - " + bodyEnd);
            ByteBuffer chunk = buf.asReadOnlyBuffer();

            chunk.position(bodyStart);
            chunk.limit(bodyEnd);

            callback.part(headers, chunk);
        }

        action end_parts {
            System.out.println("~~~~ ALL DONE");
            callback.done();
            fgoto epilogue;
        }

        action something_went_wrong {
            System.out.println("Something went wrong: " + fpc);
        }

        include "multipart.rl";
    }%%

    private int cs;

    private int boundaryPos;

    private int bodyStart;
    private int bodyEnd;

    private Object headers;

    private final ByteBuffer boundary;

    private final MultipartParserCallback callback;

    %% write data;

    public MultipartParser(ByteBuffer boundary, MultipartParserCallback callback) {
        %% write init;

        this.boundary = boundary;
        this.callback = callback;
    }

    public void execute(ByteBuffer buf) {
        // Setup ragel variables
        int p   = buf.position();
        int pe  = buf.limit();
        int eof = pe + 1;

        %% getkey buf.get(p);
        %% write exec;
    }
}
