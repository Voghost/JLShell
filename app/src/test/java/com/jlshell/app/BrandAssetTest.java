package com.jlshell.app;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class BrandAssetTest {
    private static BufferedImage image(String name) throws Exception {
        try(var stream=BrandAssetTest.class.getResourceAsStream(name)){
            assertNotNull(stream,name);BufferedImage image=ImageIO.read(stream);assertNotNull(image,name);return image;
        }
    }
    @Test void applicationAndNativeViewsUseTheSameBrandMaster() throws Exception {
        BufferedImage master=image("/icons/brand/jlshell.png");assertEquals(1024,master.getWidth());assertEquals(1024,master.getHeight());
        assertTrue(master.getColorModel().hasAlpha());assertEquals(0,master.getRGB(0,0)>>>24);
        for(String name:new String[]{"app_icon.png","jlshell_app_icon.png","jlshell_app_icon_clean.png"}){
            BufferedImage copy=image("/icons/"+name);assertEquals(master.getWidth(),copy.getWidth());
            assertArrayEquals(master.getRGB(0,0,1024,1024,null,0,1024),copy.getRGB(0,0,1024,1024,null,0,1024),name);
        }
        for(int size:new int[]{16,32,48,256}){BufferedImage icon=image("/icons/app_icon_"+size+".png");assertEquals(size,icon.getWidth());assertEquals(size,icon.getHeight());}
    }
    @Test void windowsAndMacContainersContainTheExpectedRepresentations() throws Exception {
        try(var stream=getClass().getResourceAsStream("/icons/app_icon.ico")){
            assertNotNull(stream);ByteBuffer data=ByteBuffer.wrap(stream.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0,data.getShort());assertEquals(1,data.getShort());assertEquals(7,data.getShort());
            for(int i=0;i<7;i++){int start=6+i*16;int length=data.getInt(start+8),offset=data.getInt(start+12);
                assertTrue(offset+length<=data.capacity());assertEquals(0x89504e47,data.order(ByteOrder.BIG_ENDIAN).getInt(offset));data.order(ByteOrder.LITTLE_ENDIAN);}
        }
        try(var stream=getClass().getResourceAsStream("/icons/app_icon.icns")){
            assertNotNull(stream);ByteBuffer data=ByteBuffer.wrap(stream.readAllBytes());assertEquals(0x69636e73,data.getInt());assertEquals(data.capacity(),data.getInt());
        }
    }
}
