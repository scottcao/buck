// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: cd/resources/proto/common.proto

package com.facebook.buck.cd.model.common;

@javax.annotation.Generated(value="protoc", comments="annotations:CommonCDProto.java.pb.meta")
public final class CommonCDProto {
  private CommonCDProto() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_RelPathMapEntry_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_RelPathMapEntry_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_RelPath_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_RelPath_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_AbsPath_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_AbsPath_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_Path_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_Path_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\037cd/resources/proto/common.proto\"A\n\017Rel" +
      "PathMapEntry\022\025\n\003key\030\001 \001(\0132\010.RelPath\022\027\n\005v" +
      "alue\030\002 \001(\0132\010.RelPath\"\027\n\007RelPath\022\014\n\004path\030" +
      "\001 \001(\t\"\027\n\007AbsPath\022\014\n\004path\030\001 \001(\t\"\024\n\004Path\022\014" +
      "\n\004path\030\001 \001(\tB4\n!com.facebook.buck.cd.mod" +
      "el.commonB\rCommonCDProtoP\001b\006proto3"
    };
    com.google.protobuf.Descriptors.FileDescriptor.InternalDescriptorAssigner assigner =
        new com.google.protobuf.Descriptors.FileDescriptor.    InternalDescriptorAssigner() {
          public com.google.protobuf.ExtensionRegistry assignDescriptors(
              com.google.protobuf.Descriptors.FileDescriptor root) {
            descriptor = root;
            return null;
          }
        };
    com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
        }, assigner);
    internal_static_RelPathMapEntry_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_RelPathMapEntry_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_RelPathMapEntry_descriptor,
        new java.lang.String[] { "Key", "Value", });
    internal_static_RelPath_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_RelPath_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_RelPath_descriptor,
        new java.lang.String[] { "Path", });
    internal_static_AbsPath_descriptor =
      getDescriptor().getMessageTypes().get(2);
    internal_static_AbsPath_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_AbsPath_descriptor,
        new java.lang.String[] { "Path", });
    internal_static_Path_descriptor =
      getDescriptor().getMessageTypes().get(3);
    internal_static_Path_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_Path_descriptor,
        new java.lang.String[] { "Path", });
  }

  // @@protoc_insertion_point(outer_class_scope)
}