// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: src/com/facebook/buck/javacd/resources/proto/javacd.proto

package com.facebook.buck.javacd.model;

@javax.annotation.Generated(value="protoc", comments="annotations:LibraryJarBaseCommandOrBuilder.java.pb.meta")
public interface LibraryJarBaseCommandOrBuilder extends
    // @@protoc_insertion_point(interface_extends:javacd.api.v1.LibraryJarBaseCommand)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>.javacd.api.v1.RelPath pathToClasses = 3;</code>
   */
  boolean hasPathToClasses();
  /**
   * <code>.javacd.api.v1.RelPath pathToClasses = 3;</code>
   */
  com.facebook.buck.javacd.model.RelPath getPathToClasses();
  /**
   * <code>.javacd.api.v1.RelPath pathToClasses = 3;</code>
   */
  com.facebook.buck.javacd.model.RelPathOrBuilder getPathToClassesOrBuilder();

  /**
   * <code>.javacd.api.v1.RelPath rootOutput = 4;</code>
   */
  boolean hasRootOutput();
  /**
   * <code>.javacd.api.v1.RelPath rootOutput = 4;</code>
   */
  com.facebook.buck.javacd.model.RelPath getRootOutput();
  /**
   * <code>.javacd.api.v1.RelPath rootOutput = 4;</code>
   */
  com.facebook.buck.javacd.model.RelPathOrBuilder getRootOutputOrBuilder();

  /**
   * <code>.javacd.api.v1.RelPath pathToClassHashes = 5;</code>
   */
  boolean hasPathToClassHashes();
  /**
   * <code>.javacd.api.v1.RelPath pathToClassHashes = 5;</code>
   */
  com.facebook.buck.javacd.model.RelPath getPathToClassHashes();
  /**
   * <code>.javacd.api.v1.RelPath pathToClassHashes = 5;</code>
   */
  com.facebook.buck.javacd.model.RelPathOrBuilder getPathToClassHashesOrBuilder();

  /**
   * <code>.javacd.api.v1.RelPath annotationsPath = 6;</code>
   */
  boolean hasAnnotationsPath();
  /**
   * <code>.javacd.api.v1.RelPath annotationsPath = 6;</code>
   */
  com.facebook.buck.javacd.model.RelPath getAnnotationsPath();
  /**
   * <code>.javacd.api.v1.RelPath annotationsPath = 6;</code>
   */
  com.facebook.buck.javacd.model.RelPathOrBuilder getAnnotationsPathOrBuilder();

  /**
   * <code>.javacd.api.v1.UnusedDependenciesParams unusedDependenciesParams = 7;</code>
   */
  boolean hasUnusedDependenciesParams();
  /**
   * <code>.javacd.api.v1.UnusedDependenciesParams unusedDependenciesParams = 7;</code>
   */
  com.facebook.buck.javacd.model.UnusedDependenciesParams getUnusedDependenciesParams();
  /**
   * <code>.javacd.api.v1.UnusedDependenciesParams unusedDependenciesParams = 7;</code>
   */
  com.facebook.buck.javacd.model.UnusedDependenciesParamsOrBuilder getUnusedDependenciesParamsOrBuilder();
}