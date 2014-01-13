/*
 * Copyright 2014 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spdx.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import org.spdx.rdfparser.DOAPProject;
import org.spdx.rdfparser.SPDXDocument;
import org.spdx.rdfparser.SPDXFile;
import org.spdx.rdfparser.SPDXLicenseInfo;
import org.spdx.rdfparser.SpdxPackageVerificationCode;
import org.spdx.rdfparser.SpdxRdfConstants;

/**
 * Collects SPDX file information from directories.
 * 
 * The method <code>collectFilesInDirectory(File directory)</code> will scan and
 * create SPDX File information for all files in the directory.
 * 
 * File patterns can be passed to the constructor file files which should be ignored.
 * 
 * @author Gary O'Neall
 *
 */
public class SpdxFileCollector {

	// constants for mapping extensions to types.
	//TODO: These static hashsets should be converted to using properties rather than constants
	static HashSet<String> SOURCE_EXTENSION = new HashSet<String>();
	
	static {
		SOURCE_EXTENSION.add("C"); SOURCE_EXTENSION.add("H");
		SOURCE_EXTENSION.add("JAVA"); SOURCE_EXTENSION.add("CS");
		SOURCE_EXTENSION.add("JS"); SOURCE_EXTENSION.add("HH");
		SOURCE_EXTENSION.add("CC"); SOURCE_EXTENSION.add("CPP");
		SOURCE_EXTENSION.add("CXX"); SOURCE_EXTENSION.add("HPP");
		SOURCE_EXTENSION.add("ASP"); SOURCE_EXTENSION.add("BAS");
		SOURCE_EXTENSION.add("BAT"); SOURCE_EXTENSION.add("HTM");
		SOURCE_EXTENSION.add("HTML"); SOURCE_EXTENSION.add("LSP");
		SOURCE_EXTENSION.add("PAS"); SOURCE_EXTENSION.add("XML");
		SOURCE_EXTENSION.add("PAS"); SOURCE_EXTENSION.add("ADA");
		SOURCE_EXTENSION.add("VB"); SOURCE_EXTENSION.add("ASM");
		SOURCE_EXTENSION.add("CBL"); SOURCE_EXTENSION.add("COB");
		SOURCE_EXTENSION.add("F77"); SOURCE_EXTENSION.add("M3");
		SOURCE_EXTENSION.add("MK"); SOURCE_EXTENSION.add("MKE");
		SOURCE_EXTENSION.add("RMK"); SOURCE_EXTENSION.add("MOD");
		SOURCE_EXTENSION.add("PL"); SOURCE_EXTENSION.add("PM");
		SOURCE_EXTENSION.add("PRO"); SOURCE_EXTENSION.add("REX");
		SOURCE_EXTENSION.add("SM"); SOURCE_EXTENSION.add("ST");
		SOURCE_EXTENSION.add("SNO"); SOURCE_EXTENSION.add("PY");
		SOURCE_EXTENSION.add("PHP"); SOURCE_EXTENSION.add("CSS");
		SOURCE_EXTENSION.add("XSL"); SOURCE_EXTENSION.add("XSLT");
		SOURCE_EXTENSION.add("SH"); SOURCE_EXTENSION.add("XSD");
		SOURCE_EXTENSION.add("RB"); SOURCE_EXTENSION.add("RBX");		
		SOURCE_EXTENSION.add("RHTML"); SOURCE_EXTENSION.add("RUBY");
	}
	
	static HashSet<String> BINARY_EXTENSIONS = new HashSet<String>();
	static {
		BINARY_EXTENSIONS.add("EXE");	BINARY_EXTENSIONS.add("DLL");
		BINARY_EXTENSIONS.add("JAR");	BINARY_EXTENSIONS.add("CLASS");
		BINARY_EXTENSIONS.add("SO");	BINARY_EXTENSIONS.add("A");
	}
	
	static HashSet<String> ARCHIVE_EXTENSIONS = new HashSet<String>();
	static {
		ARCHIVE_EXTENSIONS.add("ZIP"); ARCHIVE_EXTENSIONS.add("EAR");
		ARCHIVE_EXTENSIONS.add("TAR"); ARCHIVE_EXTENSIONS.add("GZ");
		ARCHIVE_EXTENSIONS.add("TGZ"); ARCHIVE_EXTENSIONS.add("BZ2");
		ARCHIVE_EXTENSIONS.add("RPM"); 
	}
	
	static final String SHA1_ALGORITHM = "SHA-1";
	static final String PACKAGE_VERIFICATION_CHARSET = "UTF-8";
	private static MessageDigest digest;
	static {
		try {
			digest = MessageDigest.getInstance(SHA1_ALGORITHM);
		} catch (NoSuchAlgorithmException e) {
			digest = null;
		};
	}
	
	
	private SPDXDocument spdxDoc;
	private Pattern[] excludedFilePatterns;
	private SpdxDefaultFileInformation defaultFileInformation;
	/**
	 * All licenses found within the files
	 */
	HashSet<SPDXLicenseInfo> licensesFromFiles = new HashSet<SPDXLicenseInfo>();
	/**
	 * Map of fileName, SPDXFile for all files in the SPDX document
	 */
	HashMap<String, SPDXFile> spdxFiles = new HashMap<String, SPDXFile>();


	/**
	 * SpdxFileCollector collects SPDX file information for files
	 * @param spdxDoc SPDX document containing any existing license information.  Any new license created will be added to the SPDX document.
	 * @param excludedFilePatterns Array of patterns which, if a file name or directory name is matched, will be skipped. 
	 * @param defaultFileInformation Information on default SPDX field data for the files
	 */
	public SpdxFileCollector(SPDXDocument spdxDoc,
			Pattern[] excludedFilePatterns,
			SpdxDefaultFileInformation defaultFileInformation) {
		this.spdxDoc = spdxDoc;
		this.excludedFilePatterns = excludedFilePatterns;
		this.defaultFileInformation = defaultFileInformation;
	}

	/**
	 * Collect file information in the directory (including subdirectories).  
	 * @param directory
	 * @throws SpdxCollectionException 
	 */
	public void collectFilesInDirectory(File directory) throws SpdxCollectionException {
		if (isExcluded(directory.getName())) {
			return;
		}
		if (!directory.isDirectory()) {
			SPDXFile spdxFile = convertToSpdxFile(directory);
			spdxFiles.put(directory.getPath(), spdxFile);
			SPDXLicenseInfo[] seenLicenses = spdxFile.getSeenLicenses();
			for (int j = 0; j < seenLicenses.length; j++) {
				licensesFromFiles.add(seenLicenses[j]);
			}
			return;
		}
		File[] children = directory.listFiles();
		for (int i = 0; i < children.length; i++) {
			if (isExcluded(children[i].getName())) {
				continue;
			}
			if (children[i].isDirectory()) {
				collectFilesInDirectory(children[i]);
			} else {
				SPDXFile spdxFile = convertToSpdxFile(children[i]);
				spdxFiles.put(directory.getPath(), spdxFile);
				SPDXLicenseInfo[] seenLicenses = spdxFile.getSeenLicenses();
				for (int j = 0; j < seenLicenses.length; j++) {
					licensesFromFiles.add(seenLicenses[j]);
				}
			}
		}
	}
	
	private SPDXFile convertToSpdxFile(File file) throws SpdxCollectionException {
		String relativePath = file.getPath();
		String fileType = extensionToFileType(getExtension(file));
		String sha1 = generateSha1(file);
		SPDXLicenseInfo license;
		license = this.defaultFileInformation.getDeclaredLicense();
		String copyright = this.defaultFileInformation.getCopyright();
		String notice = this.defaultFileInformation.getNotice();
		String comment = this.defaultFileInformation.getComment();
		String[] contributors = this.defaultFileInformation.getContributors();
		DOAPProject[] artifactOf = this.defaultFileInformation.getArtifactOf();
		SPDXLicenseInfo concludedLicense = this.defaultFileInformation.getConcludedLicense();
		String licenseComment = this.defaultFileInformation.getLicenseComment();
		return new SPDXFile(relativePath, fileType, 
				sha1, concludedLicense, new SPDXLicenseInfo[] {license}, 
				licenseComment, copyright, artifactOf, comment, null, 
				contributors, notice);
	}

	public String getExtension(File file) {
		String fileName = file.getName();
		int lastDot = fileName.lastIndexOf('.');
		if (lastDot < 1) {
			return "";
		} else {
			return fileName.substring(lastDot+1);
		}
	}

	private static String extensionToFileType(String fileExtension) {
		if (fileExtension == null) {
			return SpdxRdfConstants.FILE_TYPE_OTHER;
		}
		String upperExtension = fileExtension.toUpperCase();
		if (SOURCE_EXTENSION.contains(upperExtension)) {
			return SpdxRdfConstants.FILE_TYPE_SOURCE;
		} else if (BINARY_EXTENSIONS.contains(upperExtension)) {
			return SpdxRdfConstants.FILE_TYPE_BINARY;
		} else if (ARCHIVE_EXTENSIONS.contains(upperExtension)) {
			return SpdxRdfConstants.FILE_TYPE_ARCHIVE;
		} else {
			return SpdxRdfConstants.FILE_TYPE_OTHER;
		}
	}

	private boolean isExcluded(String fileName) {
		for (int i = 0; i < excludedFilePatterns.length; i++) {
			Matcher matcher = excludedFilePatterns[i].matcher(fileName);
			if (matcher.matches()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return SPDX Files which have been acquired through the collectFilesInDirectory method
	 */
	public SPDXFile[] getFiles() {
		return spdxFiles.values().toArray(new SPDXFile[spdxFiles.size()]);
	}

	/**
	 * @return all license information used in the SPDX files
	 */
	public SPDXLicenseInfo[] getLicenseInfoFromFiles() {
		return new SPDXLicenseInfo[licensesFromFiles.size()];
	}

	public SpdxPackageVerificationCode getVerificationCode(String spdxFileName) throws NoSuchAlgorithmException {
		ArrayList<String> excludedFileNamesFromVerificationCode = new ArrayList<String>();

		if (spdxFiles.containsKey(spdxFileName)) {
			excludedFileNamesFromVerificationCode.add(spdxFileName);
		}			
		SpdxPackageVerificationCode verificationCode;
		verificationCode = calculatePackageVerificationCode(spdxFiles.values(), excludedFileNamesFromVerificationCode);
		return verificationCode;
	}

	/**
	 * Calculate the package verification code for a collection of SPDX files
	 * @param spdxFiles Files used to calculate the verification code
	 * @param excludedFileNamesFromVerificationCode List of file names to exclude
	 * @return
	 * @throws NoSuchAlgorithmException
	 */
	private SpdxPackageVerificationCode calculatePackageVerificationCode(
			Collection<SPDXFile> spdxFiles,
			ArrayList<String> excludedFileNamesFromVerificationCode) throws NoSuchAlgorithmException {
		ArrayList<String> fileChecksums = new ArrayList<String>();
		Iterator<SPDXFile> iter = spdxFiles.iterator();
		while (iter.hasNext()) {
			SPDXFile file = iter.next();
			if (includeInVerificationCode(file.getName(), excludedFileNamesFromVerificationCode)) {
				fileChecksums.add(file.getSha1());
			}
		}
		Collections.sort(fileChecksums);
		MessageDigest verificationCodeDigest = MessageDigest.getInstance("SHA-1");
		for (int i = 0;i < fileChecksums.size(); i++) {
			byte[] hashInput = fileChecksums.get(i).getBytes(Charset.forName("UTF-8"));
			verificationCodeDigest.update(hashInput);
		}
		String value = convertChecksumToString(verificationCodeDigest.digest());
		return new SpdxPackageVerificationCode(value, excludedFileNamesFromVerificationCode.toArray(
				new String[excludedFileNamesFromVerificationCode.size()]));
	}

	private boolean includeInVerificationCode(String name, ArrayList<String> excludedFileNamesFromVerificationCode) {
		for (int i = 0; i < excludedFileNamesFromVerificationCode.size(); i++) {
			if (excludedFileNamesFromVerificationCode.get(i).equals(name)) {
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Converts an array of bytes to a string compliant with the SPDX sha1 representation
	 * @param digestBytes
	 * @return
	 */
	public static String convertChecksumToString(byte[] digestBytes) {
		StringBuilder sb = new StringBuilder();   
		for (int i = 0; i < digestBytes.length; i++) {
			String hex = Integer.toHexString(0xff & digestBytes[i]);
			if (hex.length() < 2) {
				sb.append('0');
			}
			sb.append(hex);
		}
		return sb.toString();
	}
	
	/**
	 * Generate the Sha1 for a given file.  Must have read access to the file.
	 * @param file
	 * @return
	 * @throws SpdxCollectionException
	 */
	public static String generateSha1(File file) throws SpdxCollectionException {
		if (digest == null) {
			try {
				digest = MessageDigest.getInstance(SHA1_ALGORITHM);
			} catch (NoSuchAlgorithmException e) {
				throw(new SpdxCollectionException("Unable to create the message digest for generating the File SHA1"));
			}
		}
		digest.reset();
		InputStream in;
		try {
			in = new FileInputStream(file);
		} catch (IOException e1) {
			throw(new SpdxCollectionException("IO getting file content while calculating the SHA1"));
		}
		try {
			byte[] buffer = new byte[2048];
			int numBytes = in.read(buffer);
			while (numBytes >= 0) {
				digest.update(buffer, 0, numBytes);
				numBytes = in.read(buffer);
			}
			return convertChecksumToString(digest.digest());
		} catch (IOException e) {
			throw(new SpdxCollectionException("IO error reading file input stream while calculating the SHA1"));
		} finally {
			try {
			    if (in != null) {
		              in.close(); 
			    }
			} catch (IOException e) {
				throw(new SpdxCollectionException("IO error closing file input stream while calculating the SHA1"));
			}
		}
	}
}
