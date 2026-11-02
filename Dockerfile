# JRE 이미지: JDK 대비 작아 ECR 푸시와 Fargate 이미지 pull 이 빨라진다 (실행에 컴파일러 불필요)
FROM eclipse-temurin:21-jre

COPY build/libs/*SNAPSHOT.jar app.jar

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app.jar"]
