/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.extent.clipboard.io;

import com.sk89q.jnbt.ByteArrayTag;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.IntTag;
import com.sk89q.jnbt.ListTag;
import com.sk89q.jnbt.NBTInputStream;
import com.sk89q.jnbt.NamedTag;
import com.sk89q.jnbt.ShortTag;
import com.sk89q.jnbt.StringTag;
import com.sk89q.jnbt.Tag;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.registry.ItemRegistry;
import com.sk89q.worldedit.world.registry.WorldData;
import com.sk89q.worldedit.world.storage.NBTConversions;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.platform.Platform;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Reads schematic files based that are compatible with MCEdit and other editors.
 */
public class SchematicReader implements ClipboardReader {

    private static final Logger log = Logger.getLogger(SchematicReader.class.getCanonicalName());
    private final NBTInputStream inputStream;

    /**
     * Create a new instance.
     *
     * @param inputStream the input stream to read from
     */
    public SchematicReader(NBTInputStream inputStream) {
        checkNotNull(inputStream);
        this.inputStream = inputStream;
    }

    @Override
    public Clipboard read(WorldData data) throws IOException {
        // Schematic tag
        NamedTag rootTag = inputStream.readNamedTag();
        if (!rootTag.getName().equals("Schematic")) {
            throw new IOException("Tag 'Schematic' does not exist or is not first");
        }
        CompoundTag schematicTag = (CompoundTag) rootTag.getTag();

        // Check
        Map<String, Tag> schematic = schematicTag.getValue();
        if (!schematic.containsKey("Blocks")) {
            throw new IOException("Schematic file is missing a 'Blocks' tag");
        }

        // Check type of Schematic
        String materials = requireTag(schematic, "Materials", StringTag.class).getValue();
        if (!materials.equals("Alpha")) {
            throw new IOException("Schematic file is not an Alpha schematic");
        }

        // ====================================================================
        // Metadata
        // ====================================================================

        Vector origin;
        Region region;

        // Get information
        short width = requireTag(schematic, "Width", ShortTag.class).getValue();
        short height = requireTag(schematic, "Height", ShortTag.class).getValue();
        short length = requireTag(schematic, "Length", ShortTag.class).getValue();

        try {
            int originX = requireTag(schematic, "WEOriginX", IntTag.class).getValue();
            int originY = requireTag(schematic, "WEOriginY", IntTag.class).getValue();
            int originZ = requireTag(schematic, "WEOriginZ", IntTag.class).getValue();
            Vector min = new Vector(originX, originY, originZ);

            int offsetX = requireTag(schematic, "WEOffsetX", IntTag.class).getValue();
            int offsetY = requireTag(schematic, "WEOffsetY", IntTag.class).getValue();
            int offsetZ = requireTag(schematic, "WEOffsetZ", IntTag.class).getValue();
            Vector offset = new Vector(offsetX, offsetY, offsetZ);

            origin = min.subtract(offset);
            region = new CuboidRegion(min, min.add(width, height, length).subtract(Vector.ONE));
        } catch (IOException ignored) {
            origin = new Vector(0, 0, 0);
            region = new CuboidRegion(origin, origin.add(width, height, length).subtract(Vector.ONE));
        }

        // ====================================================================
        // Blocks
        // ====================================================================

        // Get blocks
        byte[] blockId = requireTag(schematic, "Blocks", ByteArrayTag.class).getValue();
        byte[] blockData = requireTag(schematic, "Data", ByteArrayTag.class).getValue();
        byte[] addId = new byte[0];
        short[] blocks = new short[blockId.length]; // Have to later combine IDs

        // We support 4096 block IDs using the same method as vanilla Minecraft, where
        // the highest 4 bits are stored in a separate byte array.
        if (schematic.containsKey("AddBlocks")) {
            addId = requireTag(schematic, "AddBlocks", ByteArrayTag.class).getValue();
        }

        // Combine the AddBlocks data with the first 8-bit block ID
        for (int index = 0; index < blockId.length; index++) {
            if ((index >> 1) >= addId.length) { // No corresponding AddBlocks index
                blocks[index] = (short) (blockId[index] & 0xFF);
            } else {
                if ((index & 1) == 0) {
                    blocks[index] = (short) (((addId[index >> 1] & 0x0F) << 8) + (blockId[index] & 0xFF));
                } else {
                    blocks[index] = (short) (((addId[index >> 1] & 0xF0) << 4) + (blockId[index] & 0xFF));
                }
            }
        }
		boolean containsMapping=schematic.containsKey("SchematicaMapping");
		if(containsMapping){
            Set<Map.Entry<String,Tag>>mapping=requireTag(schematic,"SchematicaMapping",CompoundTag.class).getValue().entrySet();
            short[]oldBlocks=blocks;
            blocks=new short[blockId.length];
            System.arraycopy(oldBlocks,0,blocks,0,blocks.length);
			Platform platform=WorldEdit.getInstance().getPlatformManager().queryCapability(Capability.WORLD_EDITING);
            for(Map.Entry<String,Tag>e:mapping){
                short id=((ShortTag)e.getValue()).getValue().shortValue();
                short newId=(short)platform.resolveItem(e.getKey());
                for(int i=0;i<oldBlocks.length;i++)
                    if(oldBlocks[i]==id)
                        blocks[i]=newId;
            }
        }
        // Need to pull out tile entities
        List<Tag> tileEntities = requireTag(schematic, "TileEntities", ListTag.class).getValue();
        Map<BlockVector, Map<String, Tag>> tileEntitiesMap = new HashMap<BlockVector, Map<String, Tag>>();

		ItemRegistry itemRegistry=data.getItemRegistry();
		for(Tag tag:tileEntities){
			if(!(tag instanceof CompoundTag))
				continue;
			int x=0,y=0,z=0;
			Map<String,Tag>values=new HashMap<String,Tag>();
			for(Map.Entry<String,Tag>entry:((CompoundTag)tag).getValue().entrySet()){
				String key=entry.getKey();
				Tag value=entry.getValue();
				if(value instanceof IntTag)
					switch(key){
						case "x":
							x=((IntTag)value).getValue().intValue();
						break;
						case "y":
							y=((IntTag)value).getValue().intValue();
						break;
						case "z":
							z=((IntTag)value).getValue().intValue();
						break;
					}
				else if(containsMapping){
					if(value instanceof CompoundTag)
						value=checkItemsInCompound((CompoundTag)value,itemRegistry);
					else if(value instanceof ListTag)
						value=checkItemsInList((ListTag)value,itemRegistry);
				}
				values.put(key,value);
			}
			BlockVector vec=new BlockVector(x,y,z);
			tileEntitiesMap.put(vec,values);
		}

        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(origin);

        // Don't log a torrent of errors
        int failedBlockSets = 0;

        for (int x = 0; x < width; ++x) {
            for (int y = 0; y < height; ++y) {
                for (int z = 0; z < length; ++z) {
                    int index = y * width * length + z * width + x;
                    BlockVector pt = new BlockVector(x, y, z);
                    BaseBlock block = new BaseBlock(blocks[index], blockData[index]);

                    if (tileEntitiesMap.containsKey(pt)) {
                        block.setNbtData(new CompoundTag(tileEntitiesMap.get(pt)));
                    }

                    try {
                        clipboard.setBlock(region.getMinimumPoint().add(pt), block);
                    } catch (WorldEditException e) {
                        switch (failedBlockSets) {
                            case 0:
                                log.log(Level.WARNING, "Failed to set block on a Clipboard", e);
                                break;
                            case 1:
                                log.log(Level.WARNING, "Failed to set block on a Clipboard (again) -- no more messages will be logged", e);
                                break;
                            default:
                        }

                        failedBlockSets++;
                    }
                }
            }
        }

        // ====================================================================
        // Entities
        // ====================================================================

        try {
            List<Tag> entityTags = requireTag(schematic, "Entities", ListTag.class).getValue();

            for (Tag tag : entityTags) {
                if (tag instanceof CompoundTag) {
                    CompoundTag compound = (CompoundTag) tag;
                    String id = compound.getString("id");
                    Location location = NBTConversions.toLocation(clipboard, compound.getListTag("Pos"), compound.getListTag("Rotation"));

                    if (!id.isEmpty()) {
                        BaseEntity state = new BaseEntity(id, compound);
                        clipboard.createEntity(location, state);
                    }
                }
            }
        } catch (IOException ignored) { // No entities? No problem
        }

        return clipboard;
    }

    private static <T extends Tag> T requireTag(Map<String, Tag> items, String key, Class<T> expected) throws IOException {
        if (!items.containsKey(key)) {
            throw new IOException("Schematic file is missing a \"" + key + "\" tag");
        }

        Tag tag = items.get(key);
        if (!expected.isInstance(tag)) {
            throw new IOException(key + " tag is not of tag type " + expected.getName());
        }

        return expected.cast(tag);
    }

    @Nullable
    private static <T extends Tag> T getTag(CompoundTag tag, Class<T> expected, String key) {
        Map<String, Tag> items = tag.getValue();

        if (!items.containsKey(key)) {
            return null;
        }

        Tag test = items.get(key);
        if (!expected.isInstance(test)) {
            return null;
        }

        return expected.cast(test);
    }
	
	private CompoundTag checkItemsInCompound(CompoundTag compound,ItemRegistry reg){
		HashMap<String,Tag>map=new HashMap<String,Tag>(compound.getValue());
		map.replaceAll((name,t)->{
			if(t instanceof CompoundTag)
				return checkItemsInCompound((CompoundTag)t,reg);
			else if(t instanceof ListTag)
				return checkItemsInList((ListTag)t,reg);
			else
				return t;
		});
		if(map.containsKey("ItemId")&&map.containsKey("id"))
			map.put("id",new ShortTag((short)(reg.createFromId(compound.getString("ItemId")).getType())));
		return new CompoundTag(map);
	}
	private ListTag checkItemsInList(ListTag list,ItemRegistry reg){
		Class<?extends Tag>type=list.getType();
		List<Tag>tagList=list.getValue();
		int size=tagList.size();
		if(type==CompoundTag.class){
			CompoundTag[]arr=tagList.toArray(new CompoundTag[size]);
			ArrayList<CompoundTag>ret=new ArrayList<CompoundTag>(size);
			for(int i=0;i<size;i++)
				ret.add(i,checkItemsInCompound(arr[i],reg));
			return new ListTag(type,ret);
		}else if(type==ListTag.class){
			ListTag[]arr=tagList.toArray(new ListTag[size]);
			ArrayList<ListTag>ret=new ArrayList<ListTag>(size);
			for(int i=0;i<size;i++)
				ret.add(i,checkItemsInList(arr[i],reg));
			return new ListTag(type,ret);
		}else
			return list;
	}
}
