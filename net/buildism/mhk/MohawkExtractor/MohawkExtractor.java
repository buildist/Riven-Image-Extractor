package net.buildism.mhk.MohawkExtractor;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

public class MohawkExtractor {

  private static final int[] BPP = new int[]{1, 4, 8, 16, 24};

  private static final int NONE = 0;
  private static final int RLE8 = 1;
  private static final int RLE_OTHER = 3;

  private static final int LZ = 1;
  private static final int LZ_OTHER = 2;
  private static final int RIVEN = 4;

  public static void main(String[] args) throws Exception {
    String rootPath = "C:/Program Files (x86)/Steam/steamapps/common/Riven";
    File base = new File(rootPath);
    for(File file : base.listFiles()) {
      if (file.getName().endsWith(".mhk")) {
        extract(file);
      }
    }
  }

  private static void extract(File archive) throws Exception{
    System.out.println(archive.getName());
    String outputPath = archive.getName().replace(".mhk", "");
    File outputDir = new File(outputPath);
    if (!outputDir.isDirectory()) {
      outputDir.mkdir();
    }
    DataInputStream in = new DataInputStream(new FileInputStream(archive));
    in.readInt(); // "MHWK" signature
    int size = in.readInt();
    byte[] bytes = new byte[size];
    in.readFully(bytes);
    ByteBuffer bb = ByteBuffer.allocate(size);
    bb.put(bytes);
    bb.flip();

    bb.getInt(); // "RSRC"
    bb.getShort(); // version (0x100)
    bb.getShort(); // unused
    bb.getInt(); // size again
    int resourceDirOffset = bb.getInt() - 8; // subtract file header length
    int fileTableOffset = ushort(bb.getShort());
    int fileTableLength = ushort(bb.getShort());

    Map<String, TypeInfo> resourceTypes = new HashMap<>();
    bb.position(resourceDirOffset);
    int resourceNameListOffset = ushort(bb.getShort());
    int typeNameCount = ushort(bb.getShort());
    for (int i = 0; i < typeNameCount; i++){
      byte[] typeBytes = new byte[4];
      bb.get(typeBytes);
      String type = new String(typeBytes);
      resourceTypes.put(type, new TypeInfo(ushort(bb.getShort()), ushort(bb.getShort())));
    }

    for(String type : resourceTypes.keySet()) {
      TypeInfo typeInfo = resourceTypes.get(type);
      bb.position(resourceDirOffset + resourceTypes.get(type).resourceTableOffset);
      int resourceCount = ushort(bb.getShort());
      for (int i = 0; i < resourceCount; i++) {
        int resourceId = ushort(bb.getShort());
        int resourceIndexInFileTable = ushort(bb.getShort());
        typeInfo.resourceIndexToResourceId.put(resourceIndexInFileTable, resourceId);
        typeInfo.resources.put(resourceId, new ResourceInfo(type, resourceIndexInFileTable - 1));
      }

      bb.position(resourceDirOffset + resourceTypes.get(type).nameTableOffset);
      int nameCount = ushort(bb.getShort());
      for (int i = 0; i < nameCount; i++) {
        int nameListOffset = ushort(bb.getShort());
        int resourceIndexInFileTable = ushort(bb.getShort());
        bb.mark();
        bb.position(resourceDirOffset + resourceNameListOffset + nameListOffset);
        byte ch = bb.get();
        String name = "";
        while(ch != 0) {
          name += Character.toString((char) ch);
          ch = bb.get();
        }
        bb.reset();
        int resourceId = typeInfo.resourceIndexToResourceId.get(resourceIndexInFileTable);
        typeInfo.resources.get(resourceId).name = name;
      }
    }

    bb.position(resourceDirOffset + fileTableOffset);
    int fileCount = bb.getInt();
    FileInfo[] files = new FileInfo[fileCount];
    for(int i = 0; i < fileCount; i++) {
      int offset = bb.getInt();
      if (i > 0 ) {
        files[i - 1].size = offset - files[i - 1].offset;
      }
      int size0 = ushort(bb.getShort());
      int size1 = ubyte(bb.get());
      int size2 = ubyte(bb.get()) & 7;
      int fileSize = size0 | (size1 << 16) | (size2 << 24);
      files[i] = new FileInfo(offset, fileSize);
      bb.getShort();
    }

    for (String type : resourceTypes.keySet()) {
      TypeInfo typeInfo = resourceTypes.get(type);
      if (type.equals("tBMP")) {
        for (int resourceId : typeInfo.resources.keySet()) {
          ResourceInfo resource = typeInfo.resources.get(resourceId);
          FileInfo file = files[resource.fileTableOffset];
          File outputFile = new File(outputDir + "/" + type + "/" + resourceId + ".png");
          if (outputFile.exists()) continue;
          outputFile.getParentFile().mkdirs();
          System.out.println(type + " " + resourceId + " " + resource);
          bb.position(file.offset - 8);

          int width = ushort(bb.getShort()) & 0x3ff;
          int height = ushort(bb.getShort()) & 0x3ff;
          int bytesPerRow = ushort(bb.getShort()) & 0x3fe;
          int compression = ushort(bb.getShort());
          int bpp = BPP[compression & 0b111];
          int secondaryCompression = (compression & 0b11110000) >> 4;
          int primaryCompression = (compression & 0b111100000000) >> 8;
          if (secondaryCompression != NONE) {
            throw new IllegalArgumentException("unsupported secondary compression: " + secondaryCompression);

          }
          if (primaryCompression != NONE && primaryCompression != RIVEN) {
            throw new IllegalArgumentException("unsupported primary compression: " + primaryCompression);
          }

          if (bpp == 24) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
              for (int x = 0; x < width; x++) {
                int b = ubyte(bb.get());
                int g = ubyte(bb.get());
                int r = ubyte(bb.get());
                Color color = new Color(r, g, b);
                image.setRGB(x, y, color.getRGB());
              }
              for (int i = 0; i < width; i++) {
                bb.get();
              }
            }
            ImageIO.write(image, "png", new FileOutputStream(outputFile));
            continue;
          }

          bb.getShort();
          bb.get(); // bits per color, always 24
          int colorCount = ubyte(bb.get()) + 1;
          Color[] colors = new Color[colorCount];
          for (int i = 0; i < colorCount; i++) {
            int b = ubyte(bb.get());
            int g = ubyte(bb.get());
            int r = ubyte(bb.get());
            colors[i] = new Color(r, g, b);
          }
          if (primaryCompression == NONE) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
              for (int x = 0; x < bytesPerRow; x++) {
                int colorIndex = ubyte(bb.get());
                Color color = colors[colorIndex];
                if (x < width) image.setRGB(x, y, color.getRGB());
              }
            }
            ImageIO.write(image, "png", new FileOutputStream(outputFile));
          } else {
            bb.getInt(); // unknown
            int dataLength = file.offset + file.size - bb.position() - 8;
            byte[] data = new byte[dataLength];
            bb.get(data);
            int[] image = new int[bytesPerRow * height];
            int p = 0;
            int q = 0;
            while (p < data.length) {
              int cmd = ubyte(ubyte(data[p]));
              p++;
              if (cmd == 0) {
                // End of stream: when reaching it, the decoding is complete.
                break;
              } else if (cmd <= 0x3f) {
                // Output n pixel duplets, where n is the command value itself. Pixel data comes
                // immediately after the command as 2*n bytes representing direct indices in the 8-bit
                // color table.
                for (int i = 0; i < cmd; i++) {
                  image[q] = ubyte(data[p]);
                  image[q + 1] = ubyte(data[p + 1]);
                  p += 2;
                  q += 2;
                }
              } else if (cmd <= 0x7f) {
                // Repeat last 2 pixels n times, where n = command_value & 0x3F.
                int pixel1 = image[q - 2];
                int pixel2 = image[q - 1];
                for (int i = 0; i < (cmd & 0x3f); i++) {
                  image[q] = pixel1;
                  image[q + 1] = pixel2;
                  q += 2;
                }
              } else if (cmd <= 0xbf) {
                // Repeat last 4 pixels n times, where n = command_value & 0x3F.
                int pixel1 = image[q - 4];
                int pixel2 = image[q - 3];
                int pixel3 = image[q - 2];
                int pixel4 = image[q - 1];
                for (int i = 0; i < (cmd & 0x3f); i++) {
                  image[q] = pixel1;
                  image[q + 1] = pixel2;
                  image[q + 2] = pixel3;
                  image[q + 3] = pixel4;
                  q += 4;
                }
              } else {
                // Begin of a subcommand stream. This is like the main command stream, but contains
                // another set of commands which are somewhat more specific and a bit more complex.
                // This command says that command_value & 0x3F subcommands will follow.
                int subCount = cmd & 0x3f;
                for (int i = 0; i < subCount; i++) {
                  int sub = ubyte(data[p]);
                  p++;
                  if (sub >= 0x01 && sub <= 0x0f) {
                    // 0000mmmm
                    // Repeat duplet at relative position -m, where m is given in duplets. So if m=1,
                    // repeat the last duplet.
                    int offset = -(sub & 0b00001111) * 2;
                    image[q] = image[q + offset];
                    image[q + 1] = image[q + offset + 1];
                    q += 2;
                  } else if (sub == 0x10) {
                    // Repeat last duplet, but change second pixel to p.
                    image[q] = image[q - 2];
                    image[q + 1] = ubyte(data[p]);
                    p++;
                    q += 2;
                  } else if (sub >= 0x11 && sub <= 0x1f) {
                    // 0001mmmm
                    // Output the first pixel of last duplet, then pixel at relative position -m. m is
                    // given in pixels. (relative to the second pixel!)
                    int offset = -(sub & 0b00001111) + 1;
                    image[q] = image[q - 2];
                    image[q + 1] = image[q + offset];
                    q += 2;
                  } else if (sub >= 0x20 && sub <= 0x2f) {
                    // 0010xxxx
                    // Repeat last duplet, but add x to second pixel.
                    image[q] = image[q - 2];
                    image[q + 1] = image[q - 1] + (sub & 0b00001111);
                    q += 2;
                  } else if (sub >= 0x30 && sub <= 0x3f) {
                    // 0011xxxx
                    // Repeat last duplet, but subtract x from second pixel.
                    image[q] = image[q - 2];
                    image[q + 1] = image[q - 1] - (sub & 0b00001111);
                    q += 2;
                  } else if (sub == 0x40) {
                    // Repeat last duplet, but change first pixel to p.
                    image[q] = ubyte(data[p]);
                    image[q + 1] = image[q - 1];
                    p++;
                    q += 2;
                  } else if (sub >= 0x41 && sub <= 0x4f) {
                    // 0100mmmm
                    // Output pixel at relative position -m, then second pixel of last duplet.
                    int offset = -(sub & 0b00001111);
                    image[q] = image[q + offset];
                    image[q + 1] = image[q - 1];
                    q += 2;
                  } else if (sub == 0x50) {
                    // Output two absolute pixel values, p1 and p2.
                    image[q] = ubyte(data[p]);
                    image[q + 1] = ubyte(data[p + 1]);
                    p += 2;
                    q += 2;
                  } else if (sub >= 0x51 && sub <= 0x57) {
                    // 01010mmm p
                    // Output pixel at relative position -m, then absolute pixel value p.
                    int offset = -(sub & 0b00000111);
                    image[q] = image[q + offset];
                    image[q + 1] = ubyte(data[p]);
                    p++;
                    q += 2;
                  } else if (sub >= 0x59 && sub <= 0x5f) {
                    // 01011mmm p
                    // Output absolute pixel value p, then pixel at relative position -m.
                    // (relative to the second pixel!)
                    int offset = -(sub & 0b00000111) + 1;
                    image[q] = ubyte(data[p]);
                    image[q + 1] = image[q + offset];
                    p++;
                    q += 2;
                  } else if (sub >= 0x60 && sub <= 0x6f) {
                    // 0110xxxx p
                    // Output absolute pixel value p, then (second pixel of last duplet) + x.
                    image[q] = ubyte(data[p]);
                    image[q + 1] = image[q - 1] + (sub & 0b00001111);
                    p++;
                    q += 2;
                  } else if (sub >= 0x70 && sub <= 0x7f) {
                    // 0111xxxx p
                    // Output absolute pixel value p, then (second pixel of last duplet) - x.
                    image[q] = ubyte(data[p]);
                    image[q + 1] = image[q - 1] - (sub & 0b00001111);
                    p++;
                    q += 2;
                  } else if (sub >= 0x80 && sub <= 0x8f) {
                    // 1000xxxx
                    // Repeat last duplet adding x to the first pixel.
                    image[q] = image[q - 2] + (sub & 0b00001111);
                    image[q + 1] = image[q - 1];
                    q += 2;
                  } else if (sub >= 0x90 && sub <= 0x9f) {
                    // 1001xxxx p
                    // Output (first pixel of last duplet) + x, then absolute pixel value p.
                    image[q] = image[q - 2] + (sub & 0b00001111);
                    image[q + 1] = ubyte(data[p]);
                    p++;
                    q += 2;
                  } else if (sub == 0xa0) {
                    // 0xa0 xxxxyyyy
                    // Repeat last duplet, adding x to the first pixel and y to the second.
                    int x = (ubyte(data[p]) & 0b11110000) >> 4;
                    int y = ubyte(data[p]) & 0b00001111;
                    image[q] = image[q - 2] + x;
                    image[q + 1] = image[q - 1] + y;
                    p++;
                    q += 2;
                  } else if (sub == 0xb0) {
                    // 0xb0 xxxxyyyy
                    // Repeat last duplet, adding x to the first pixel and subtracting y to the
                    // second.
                    int x = (ubyte(data[p]) & 0b11110000) >> 4;
                    int y = ubyte(data[p]) & 0b00001111;
                    image[q] = image[q - 2] + x;
                    image[q + 1] = image[q - 1] - y;
                    p++;
                    q += 2;
                  } else if (sub >= 0xc0 && sub <= 0xcf) {
                    // 1100xxxx
                    // Repeat last duplet subtracting x from first pixel.
                    image[q] = image[q - 2] - (sub & 0b00001111);
                    image[q + 1] = image[q - 1];
                    q += 2;
                  } else if (sub >= 0xd0 && sub <= 0xdf) {
                    // 1101xxxx p
                    // Output (first pixel of last duplet) - x, then absolute pixel value p.
                    image[q] = image[q - 2] - (sub & 0b00001111);
                    image[q + 1] = ubyte(data[p]);
                    p++;
                    q += 2;
                  } else if (sub == 0xe0) {
                    // 0xe0 xxxxyyyy
                    // Repeat last duplet, subtracting x from first pixel and adding y to second.
                    int x = (ubyte(data[p]) & 0b11110000) >> 4;
                    int y = ubyte(data[p]) & 0b00001111;
                    image[q] = image[q - 2] - x;
                    image[q + 1] = image[q - 1] + y;
                    p++;
                    q += 2;
                  } else if (sub == 0xf0 || sub == 0xff) {
                    // 0xfx xxxxyyyy
                    // Repeat last duplet, subtracting x from first pixel and y from second.
                    int x = ((sub & 0b00001111) << 4) | ((ubyte(data[p]) & 0b11110000) >> 4);
                    int y = ubyte(data[p]) & 0b00001111;
                    image[q] = image[q - 2] - x;
                    image[q + 1] = image[q - 1] - y;
                    p++;
                    q += 2;
                  } else if ((sub & 0b10100000) == 0b10100000 && sub != 0xfc) {
                    // 1x1xxxmm mmmmmmmm
                    // Repeat n duplets from relative position -m (given in pixels, not duplets). If r
                    // is 0, another byte follows and the last pixel is set to that value. n and r come
                    // from the table on the right.
                    int n, r;
                    if (sub >= 0xa4 && sub <= 0xa7) {
                      n = 2;
                      r = 0;
                    } else if (sub >= 0xa8 && sub <= 0xab) {
                      n = 2;
                      r = 1;
                    } else if (sub >= 0xac && sub <= 0xaf) {
                      n = 3;
                      r = 0;
                    } else if (sub >= 0xb4 && sub <= 0xb7) {
                      n = 3;
                      r = 1;
                    } else if (sub >= 0xb8 && sub <= 0xbb) {
                      n = 4;
                      r = 0;
                    } else if (sub >= 0xbc && sub <= 0xbf) {
                      n = 4;
                      r = 1;
                    } else if (sub >= 0xe4 && sub <= 0xe7) {
                      n = 5;
                      r = 0;
                    } else if (sub >= 0xe8 && sub <= 0xeb) {
                      n = 5;
                      r = 1;
                    } else if (sub >= 0xec && sub <= 0xef) {
                      n = 6;
                      r = 0;
                    } else if (sub >= 0xf4 && sub <= 0xf7) {
                      n = 6;
                      r = 1;
                    } else if (sub >= 0xf8 && sub <= 0xfb) {
                      n = 7;
                      r = 0;
                    } else {
                      throw new RuntimeException("subcommand: " + sub);
                    }

                    int offset = -(ubyte(data[p]) | ((sub & 0b00000011) << 8));
                    p++;
                    for (int j = 0; j < n; j++) {
                      image[q + 2 * j] = image[q + offset + 2 * j];
                      image[q + 2 * j + 1] = image[q + offset + 2 * j + 1];
                    }
                    q += 2 * n;
                    if (r == 0) {
                      image[q - 1] = ubyte(data[p]);
                      p++;
                    }
                  } else if (sub == 0xfc) {
                    // 0xfc nnnnnrmm mmmmmmmm (p)
                    // Repeat n+2 duplets from relative position -m (given in pixels, not duplets). If
                    // r is 0, another byte p follows and the last pixel is set to absolute value p.
                    int n = (ubyte(data[p]) & 0b11111000) >> 3;
                    int r = (ubyte(data[p]) & 0b00000100) >> 2;
                    int offset = -(ubyte(data[p + 1]) | ((ubyte(data[p]) & 0b00000011) << 8));

                    for (int j = 0; j < n + 2; j++) {
                      image[q + 2 * j] = image[q + offset + 2 * j];
                      image[q + 2 * j + 1] = image[q + offset + 2 * j + 1];
                    }
                    p += 2;
                    q += 2 * n + 4;
                    if (r == 0) {
                      image[q - 1] = ubyte(data[p]);
                      p++;
                    }
                  } else {
                    throw new RuntimeException("subcommand: " + sub);
                  }
                }
              }
            }
            BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            int i = 0;
            for (int y = 0; y < height; y++) {
              for (int x = 0; x < bytesPerRow; x++) {
                int colorIndex = ubyte(image[i]);
                Color color = colors[colorIndex & 0xff];
                if (x < width) output.setRGB(x, y, color.getRGB());
                i++;
              }
            }
            ImageIO.write(output, "png", new FileOutputStream(outputFile));
          }
        }
      } else if(type.equals("tMOV")) {
        for (int resourceId : typeInfo.resources.keySet()) {
          ResourceInfo resource = typeInfo.resources.get(resourceId);
          FileInfo file = files[resource.fileTableOffset];
          File outputFile = new File(outputDir + "/" + type + "/" + resourceId + ".mov");
          if (outputFile.exists()) continue;
          outputFile.getParentFile().mkdirs();
          bb.position(file.offset - 8);
          FileOutputStream fileOut = new FileOutputStream(outputFile);

          byte[] fileBytes = new byte[file.size];
          bb.get(fileBytes);
          List<Integer> stcoOffsets =
              find(fileBytes, new byte[]{0x73, 0x74, 0x63, 0x6f, 0x00, 0x00, 0x00, 0x00});
          ByteBuffer movBuffer = ByteBuffer.wrap(fileBytes);

          if (stcoOffsets.isEmpty()) {
            System.out.println(resourceId + " " + resource + " ");
          } else {
            for(int stcoOffset : stcoOffsets) {
              movBuffer.position(stcoOffset);
              movBuffer.getInt(); // 'stco'
              movBuffer.get(); // version;
              movBuffer.get(); // flags
              movBuffer.get();
              movBuffer.get();
              int entryCount = movBuffer.getInt();

              for (int i = 0; i < entryCount; i++) {
                movBuffer.mark();
                int offset = movBuffer.getInt() - file.offset;
                movBuffer.reset();
                movBuffer.putInt(offset);
              }
            }
          }

          movBuffer.flip();
          fileOut.write(movBuffer.array());
          fileOut.close();
        }
      }
    }
  }

  private static List<Integer> find(byte[] array, byte[] target) {
    List<Integer> result = new ArrayList<>();
    if (target.length == 0) {
      return result;
    }

    outer:
    for (int i = 0; i < array.length - target.length + 1; i++) {
      for (int j = 0; j < target.length; j++) {
        if (array[i + j] != target[j]) {
          continue outer;
        }
      }
      result.add(i);
    }
    return result;
  }

  private static int ushort(int value) {
    return value < 0 ? value + 65536 : value;
  }

  private static int ubyte(int value) {
    return value < 0 ? value + 256 : value;
  }

  static class TypeInfo {
    final int resourceTableOffset;
    final int nameTableOffset;
    final Map<Integer, Integer> resourceIndexToResourceId = new HashMap<>();
    final Map<Integer, ResourceInfo> resources = new HashMap<>();

    TypeInfo(int resourceTableOffset, int nameTableOffset) {
      this.resourceTableOffset = resourceTableOffset;
      this.nameTableOffset = nameTableOffset;
    }

    @Override
    public String toString() {
      return resourceTableOffset + " " + nameTableOffset;
    }
  }
  static class ResourceInfo {
    final String type;
    final int fileTableOffset;
    String name;

    ResourceInfo(String type, int fileTableOffset) {
      this.type = type;
      this.fileTableOffset = fileTableOffset;
    }

    @Override
    public String toString() {
      return fileTableOffset+" "+name;
    }
  }

  static class FileInfo {
    final int offset;
    int size;

    FileInfo(int offset, int size) {
      this.offset = offset;
      this.size = size;
    }

    @Override
    public String toString() {
      return  offset + " " + size;
    }
  }
}