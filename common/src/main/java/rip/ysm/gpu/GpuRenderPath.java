package rip.ysm.gpu;

import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.yesstevemodel.mixin.client.RenderSystemAccessor;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class GpuRenderPath {
    private static final float[] rootPoseScratch = new float[16];
    private static final float[] rootNormalScratch = new float[9];
    private static final float[] projScratch = new float[16];
    private static final Matrix4f projMVScratch = new Matrix4f();
    private static final Vector3f[] currentLights = new Vector3f[2];
    private static final ConcurrentHashMap<Long, GpuMesh> meshMap = new ConcurrentHashMap<>();
    private static final AtomicLong ref = new AtomicLong(1);
    private static final Matrix4f pivotAbsScratchMat = new Matrix4f();
    private static int[] pivotAbsPathScratch = new int[64];

    public static boolean tryRender(
            GeoModel model,
            PoseStack.Pose pose,
            float[] boneParams,
            float[] stateBuffer,
            int textureIndex,
            int renderPartMask,
            int packedLight,
            int packedOverlay,
            float r, float g, float b, float a,
            ResourceLocation textureLocation
    ) {
        if (!GpuCapability.isAvailable()) return false;
        if (!BoneSkinShader.ensureCompiled()) return false;
        if (model.bakedBones == null || model.bakedBones.isEmpty()) return false;

        if (model.gpuMeshHandle == 0) {
            GpuMesh mesh = GpuMeshBuilder.build(model);
            if (mesh == null) return false;
            model.gpuMeshHandle = encodeMeshRef(mesh);
        }
        GpuMesh mesh = decodeMeshRef(model.gpuMeshHandle);
        if (mesh == null) return false;

        Matrix4f rootPose = pose.pose();
        Matrix3f rootNormal = pose.normal();
        Matrix4f projMat = RenderSystem.getProjectionMatrix();
        Matrix4f mvMat = RenderSystem.getModelViewMatrix();

        rootPose.get(rootPoseScratch);
        rootNormal.get(rootNormalScratch);
        projMat.mul(mvMat, projMVScratch);
        projMVScratch.get(projScratch);

        ByteBuffer boneBuf = mesh.perFrameBoneBuffer;
        boneBuf.clear();

        updatePivotAbsStateBuffer(model, boneParams, stateBuffer);

        GeoModel.nComputeBoneMatrices(mesh.pointer, rootPoseScratch, rootNormalScratch, boneParams, packedLight, boneBuf);
        boneBuf.position(0);
        boneBuf.limit(mesh.boneCount * 144);

        drawNow(mesh, boneBuf, projScratch, model.isTranslucentTexture(textureIndex), renderPartMask, packedOverlay, r, g, b, a, textureLocation);
        return true;
    }

    private static void drawNow(GpuMesh mesh, ByteBuffer boneBuf, float[] projScratch, boolean translucent, int renderPartMask, int packedOverlay, float r, float g, float b, float a, ResourceLocation textureLocation) {
        Minecraft mc = Minecraft.getInstance();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._depthFunc(GL11.GL_LEQUAL);

        AbstractTexture modelTex = mc.getTextureManager().getTexture(textureLocation);
        int modelTexId = modelTex.getId();

        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 2);
        mc.gameRenderer.lightTexture().turnOnLightLayer();

        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 1);
        mc.gameRenderer.overlayTexture().setupOverlayColor();
        GlStateManager._bindTexture(RenderSystem.getShaderTexture(1)); // overlayTexture里的texture没getter，固定bind 1

        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(modelTexId);

        int boneSlot = acquireBoneSlot(mesh.boneCount * 144);
        int ssbo = BONE_RING_IDS[boneSlot];
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, boneBuf);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BoneSkinShader.ssbo, ssbo);

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        float[] fogColor = RenderSystem.getShaderFogColor();
        int fogShape = RenderSystem.getShaderFogShape().getIndex();

        GlStateManager._glUseProgram(BoneSkinShader.program());
        if (BoneSkinShader.locProj() >= 0) GL20.glUniformMatrix4fv(BoneSkinShader.locProj(), false, projScratch);
        if (BoneSkinShader.locColor() >= 0) GL20.glUniform4f(BoneSkinShader.locColor(), r, g, b, a);
        if (BoneSkinShader.locOverlay() >= 0) GL20.glUniform1i(BoneSkinShader.locOverlay(), packedOverlay);
        if (BoneSkinShader.locFogStart() >= 0) GL20.glUniform1f(BoneSkinShader.locFogStart(), fogStart);
        if (BoneSkinShader.locFogEnd() >= 0) GL20.glUniform1f(BoneSkinShader.locFogEnd(), fogEnd);

        if (BoneSkinShader.locFogColor() >= 0)
            GL20.glUniform4f(BoneSkinShader.locFogColor(), fogColor[0], fogColor[1], fogColor[2], fogColor[3]);

        if (BoneSkinShader.locFogShape() >= 0) GL20.glUniform1i(BoneSkinShader.locFogShape(), fogShape);

        refreshLights();

        if (BoneSkinShader.locLight0() >= 0)
            GL20.glUniform3f(BoneSkinShader.locLight0(), currentLights[0].x, currentLights[0].y, currentLights[0].z);
        if (BoneSkinShader.locLight1() >= 0)
            GL20.glUniform3f(BoneSkinShader.locLight1(), currentLights[1].x, currentLights[1].y, currentLights[1].z);

        GlStateManager._glBindVertexArray(mesh.vao);

        int offsetBytes = mesh.indexOffsetBytes(renderPartMask);
        int drawCount = mesh.indexDrawCount(renderPartMask);
        if (drawCount > 0) {
            if (BoneSkinShader.locAlphaMode() >= 0) GL20.glUniform1i(BoneSkinShader.locAlphaMode(), 1);
            GL11.glDrawElements(GL11.GL_TRIANGLES, drawCount, GL11.GL_UNSIGNED_INT, offsetBytes);

            if (translucent) {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                if (BoneSkinShader.locAlphaMode() >= 0) GL20.glUniform1i(BoneSkinShader.locAlphaMode(), 2);
                GL11.glDrawElements(GL11.GL_TRIANGLES, drawCount, GL11.GL_UNSIGNED_INT, offsetBytes);
                RenderSystem.disableBlend();
            }
        }

        fenceBoneSlot(boneSlot);

        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BoneSkinShader.ssbo, 0);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        GlStateManager._glUseProgram(0);

        com.mojang.blaze3d.vertex.BufferUploader.invalidate();
        GlStateManager._glBindVertexArray(0);

        // 把渲染状态复位成实体渲染阶段 MC 期望的值，避免裸 GL 绘制后与渲染管线不同步。
        mc.gameRenderer.lightTexture().turnOffLightLayer();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    // 骨骼数据环形缓冲：同一模型一帧内会被绘制多次（界面预览 + 网格按钮 + HUD/世界），
    // 若共用一个 SSBO，后写的骨骼数据可能被仍在排队中的绘制读到，导致姿态互相抢用 → 闪烁/撕裂。
    // 用多个缓冲严格轮转，连续绘制各自使用不同存储；槽位懒分配，未使用的槽位不占显存。
    // 复用槽位前用 GL fence 等该槽位上一次绘制真正完成：不同设备驱动的命令队列深度不同，
    // 队列较深的设备若不等待，CPU 会覆盖 GPU 仍在读取的骨骼数据 → 模型撕裂。
    private static final int BONE_RING_SIZE = 128;
    private static final int[] BONE_RING_IDS = new int[BONE_RING_SIZE];
    private static final int[] BONE_RING_CAPS = new int[BONE_RING_SIZE];
    private static final long[] BONE_RING_FENCES = new long[BONE_RING_SIZE];
    private static int BONE_RING_COUNT = 0;
    private static int BONE_RING_CURSOR = 0;

    private static int acquireBoneSlot(int bytes) {
        int idx = BONE_RING_CURSOR;
        if (idx >= BONE_RING_COUNT) {
            BONE_RING_IDS[idx] = GlStateManager._glGenBuffers();
            BONE_RING_CAPS[idx] = 0;
            BONE_RING_COUNT = idx + 1;
        }

        long fence = BONE_RING_FENCES[idx];
        if (fence != 0L) {
            // 等该槽位上一次绘制完成（最多等 1 秒；正常情况下早已完成，不会真的阻塞）。
            GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
            GL32.glDeleteSync(fence);
            BONE_RING_FENCES[idx] = 0L;
        }

        if (BONE_RING_CAPS[idx] < bytes) {
            // 只扩当前槽位，其余槽位保持不动，避免影响 GPU 队列中尚未完成的绘制。
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, BONE_RING_IDS[idx]);
            GL45.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) bytes, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            BONE_RING_CAPS[idx] = bytes;
        }
        BONE_RING_CURSOR = (idx + 1) % BONE_RING_SIZE;
        return idx;
    }

    // 绘制结束后为该槽位打一个 fence，下次复用它之前先等它完成。
    private static void fenceBoneSlot(int idx) {
        BONE_RING_FENCES[idx] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    private static void refreshLights() {
        Vector3f[] arr = RenderSystemAccessor.ysm$getShaderLightDirections();
        currentLights[0] = (arr != null && arr.length > 0 && arr[0] != null) ? arr[0] : new Vector3f(0.2f, 1.0f, -0.7f).normalize();
        currentLights[1] = (arr != null && arr.length > 1 && arr[1] != null) ? arr[1] : new Vector3f(-0.2f, 1.0f, 0.7f).normalize();
    }

    public static void disposeMesh(GeoModel model) {
        if (model.gpuMeshHandle == 0) return;
        GpuMesh mesh = meshMap.remove(model.gpuMeshHandle);
        if (mesh != null) mesh.dispose();
        model.gpuMeshHandle = 0;
    }

    public static GpuMesh getOrBuildMesh(GeoModel model) {
        if (model.gpuMeshHandle == 0) {
            GpuMesh mesh = GpuMeshBuilder.build(model);
            if (mesh == null) return null;
            model.gpuMeshHandle = encodeMeshRef(mesh);
        }
        return decodeMeshRef(model.gpuMeshHandle);
    }

    private static long encodeMeshRef(GpuMesh mesh) {
        long ref = GpuRenderPath.ref.getAndIncrement();
        meshMap.put(ref, mesh);
        return ref;
    }

    private static GpuMesh decodeMeshRef(long ref) {
        return meshMap.get(ref);
    }

    private static void updatePivotAbsStateBuffer(GeoModel model, float[] boneParams, float[] stateBuffer) {
        if (stateBuffer == null || boneParams == null) return;
        if (model.bakedBones == null || model.bakedBones.isEmpty()) return;

        int boneCount = model.bakedBones.size();

        for (int i = 0; i < boneCount; i++) {
            int pOffset = i * 12;
            if (pOffset + 11 >= boneParams.length) break;

            float unk3 = boneParams[pOffset + 11];
            if (unk3 != 1.0f) continue;

            int sOffset = i * 4;
            if (sOffset + 2 >= stateBuffer.length) continue;

            computeOnePivotAbs(i, model.bakedBones, boneParams, stateBuffer, sOffset);
        }
    }

    private static void computeOnePivotAbs(int targetIdx, List<GeoModel.BakedBone> bones, float[] boneParams, float[] stateBuffer, int stateOffset) {
        int depth = 0;
        int idx = targetIdx;

        while (idx != -1) {
            if (depth >= pivotAbsPathScratch.length) {
                int[] newPath = new int[pivotAbsPathScratch.length * 2];
                System.arraycopy(pivotAbsPathScratch, 0, newPath, 0, pivotAbsPathScratch.length);
                pivotAbsPathScratch = newPath;
            }

            pivotAbsPathScratch[depth++] = idx;
            idx = bones.get(idx).parentIdx;
        }

        Matrix4f localMat = pivotAbsScratchMat.identity();
        boolean isVisible = true;

        for (int p = depth - 1; p >= 0; p--) {
            int boneIdx = pivotAbsPathScratch[p];
            GeoModel.BakedBone bone = bones.get(boneIdx);

            int pOffset = boneIdx * 12;
            if (pOffset + 11 >= boneParams.length) return;

            float animRx = boneParams[pOffset];
            float animRy = boneParams[pOffset + 1];
            float animRz = boneParams[pOffset + 2];
            float animTx = boneParams[pOffset + 3];
            float animTy = boneParams[pOffset + 4];
            float animTz = boneParams[pOffset + 5];
            float animSx = boneParams[pOffset + 6];
            float animSy = boneParams[pOffset + 7];
            float animSz = boneParams[pOffset + 8];

            if (animSx == 0.0f && animSy == 0.0f && animSz == 0.0f) {
                isVisible = false;
            }

            if (!isVisible) {
                return;
            }

            localMat.translate((bone.pivotX - animTx) * 0.0625f, (bone.pivotY + animTy) * 0.0625f, (bone.pivotZ + animTz) * 0.0625f);

            localMat.rotateZ(animRz);
            localMat.rotateY(animRy);
            localMat.rotateX(animRx);

            if (animSx != 1.0f || animSy != 1.0f || animSz != 1.0f) {
                localMat.scale(animSx, animSy, animSz);
            }

            if (boneIdx == targetIdx) {
                stateBuffer[stateOffset] = -localMat.m30() * 16.0f;
                stateBuffer[stateOffset + 1] = localMat.m31() * 16.0f;
                stateBuffer[stateOffset + 2] = localMat.m32() * 16.0f;
                return;
            }

            localMat.translate(-bone.pivotX / 16.0f, -bone.pivotY / 16.0f, -bone.pivotZ / 16.0f);
        }
    }
}
