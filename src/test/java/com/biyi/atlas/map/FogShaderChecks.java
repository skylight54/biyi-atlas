package com.biyi.atlas.map;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Arrays;

/** Real GPU checks in an invisible window; does not attach to or control Minecraft. */
public final class FogShaderChecks {
    private static final int SIZE=64;
    public static void main(String[] args)throws Exception{
        if(!GLFW.glfwInit())throw new IllegalStateException("GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window=GLFW.glfwCreateWindow(SIZE,SIZE,"Biyi fog GPU verification",0,0);
        if(window==0){GLFW.glfwTerminate();throw new IllegalStateException("Hidden GL context failed");}
        try{
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            Path root=Path.of(args[0]);
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            var config=com.google.gson.JsonParser.parseString(Files.readString(root.resolve("atlas_fog.json"))).getAsJsonObject();
            var blend=net.minecraft.client.renderer.ShaderInstance.parseBlendNode(config.getAsJsonObject("blend"));
            int vs=compile(GL20.GL_VERTEX_SHADER,Files.readString(root.resolve("atlas_fog.vsh")));
            int fs=compile(GL20.GL_FRAGMENT_SHADER,Files.readString(root.resolve("atlas_fog.fsh")));
            int program=GL20.glCreateProgram();GL20.glAttachShader(program,vs);GL20.glAttachShader(program,fs);
            GL20.glBindAttribLocation(program,0,"Position");GL20.glBindAttribLocation(program,1,"UV0");GL20.glLinkProgram(program);
            require(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)!=0,GL20.glGetProgramInfoLog(program));GL20.glUseProgram(program);
            float[] identity={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
            GL20.glUniformMatrix4fv(uniform(program,"ModelViewMat"),false,identity);
            GL20.glUniformMatrix4fv(uniform(program,"ProjMat"),false,identity);
            GL20.glUniform4f(uniform(program,"ColorModulator"),1,1,1,1);
            GL20.glUniform3f(uniform(program,"Tint"),1,1,1);
            GL20.glUniform1i(uniform(program,"Sampler0"),0);GL20.glUniform1i(uniform(program,"Sampler1"),1);
            int vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);
            int vbo=GL15.glGenBuffers();GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,new float[]{-1,-1,0,-2,-2, 1,-1,0,2,-2, 1,1,0,2,2,
                    -1,-1,0,-2,-2, 1,1,0,2,2, -1,1,0,-2,2},GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,20,0);
            GL20.glEnableVertexAttribArray(1);GL20.glVertexAttribPointer(1,2,GL11.GL_FLOAT,false,20,12);
            byte[] red={(byte)255,0,0,(byte)255};texture(0,red,1,1);
            int mask=texture(1,new byte[]{0,0,0,(byte)128},1,1);
            GL11.glViewport(0,0,SIZE,SIZE);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glEnable(GL11.GL_BLEND);
            // Match the held map's RGBA texture rather than a window with unspecified alpha bits.
            int target=texture(2,new byte[SIZE*SIZE*4],SIZE,SIZE),fbo=GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,target,0);
            require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)==GL30.GL_FRAMEBUFFER_COMPLETE,"RGBA paper framebuffer incomplete");
            com.biyi.atlas.client.AtlasFogShader.configureBlend();blend.apply();
            bounds(program,-1,-1,1,1);GL20.glUniform1f(uniform(program,"OutsideAlpha"),1);
            byte[] soft=frame();int middle=(SIZE/2*SIZE+SIZE/2)*4;
            require((soft[middle]&255)>120&&(soft[middle]&255)<140&&(soft[middle+2]&255)>120,"Soft opacity must blend red fog over blue, not hard-cut");
            for(int k=3;k<soft.length;k+=4)require((soft[k]&255)==255,"Partial fog must preserve opaque paper alpha");
            require((soft[0]&255)==255&&(soft[2]&255)==0,"Exterior beyond mask bounds must remain opaque");
            GL20.glUniform1f(uniform(program,"OutsideAlpha"),0);byte[] inner=frame();
            require((inner[0]&255)==0&&(inner[2]&255)==255,"Interior fog must be transparent beyond mask bounds");
            // Other GUI draws reset blending; Minecraft caches BlendMode.apply between frames.
            for(int i=0;i<3;i++){
                com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
                com.biyi.atlas.client.AtlasFogShader.configureBlend();blend.apply();
                byte[] again=frame();
                for(int k=3;k<again.length;k+=4)require((again[k]&255)==255,"Repeated transparent fog must not punch a hole in held paper");
            }
            byte[] gradient=new byte[4*4*4];
            for(int y=0;y<4;y++)for(int x=0;x<4;x++){int k=(y*4+x)*4;gradient[k]=(byte)(40+x*50);gradient[k+1]=(byte)(20+y*60);gradient[k+3]=(byte)255;}
            texture(0,gradient,4,4);GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,mask);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,bytes(new byte[]{0,0,0,(byte)255}));
            GL20.glUniform1f(uniform(program,"OutsideAlpha"),1);bounds(program,-1,-1,1,1);byte[] narrow=frame();
            bounds(program,-3,-3,3,3);byte[] wide=frame();
            require(Arrays.equals(narrow,wide),"Cloud color must stay continuous when crossing cached mask bounds, including negative world coordinates");
            require(GL11.glGetError()==GL11.GL_NO_ERROR,"GPU check generated an OpenGL error");
            System.out.println("FogShaderChecks PASS: GLSL, RGBA paper alpha, repeated cached blending, protected exterior and continuous cloud sampling");
            GL20.glDeleteProgram(program);GL20.glDeleteShader(vs);GL20.glDeleteShader(fs);GL15.glDeleteBuffers(vbo);GL30.glDeleteVertexArrays(vao);
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
    private static int compile(int type,String text){int s=GL20.glCreateShader(type);GL20.glShaderSource(s,text);GL20.glCompileShader(s);require(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)!=0,GL20.glGetShaderInfoLog(s));return s;}
    private static int uniform(int p,String name){int u=GL20.glGetUniformLocation(p,name);require(u>=0,"Missing shader uniform: "+name);return u;}
    private static void bounds(int p,float x,float y,float r,float b){GL20.glUniform4f(uniform(p,"MaskBounds"),x,y,r,b);}
    private static ByteBuffer bytes(byte[] data){var b=BufferUtils.createByteBuffer(data.length);b.put(data).flip();return b;}
    private static int texture(int unit,byte[] data,int w,int h){GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);int t=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,t);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,w,h,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,bytes(data));return t;}
    private static byte[] frame(){GL11.glClearColor(0,0,1,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);var b=BufferUtils.createByteBuffer(SIZE*SIZE*4);GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,b);byte[] out=new byte[b.remaining()];b.get(out);return out;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
