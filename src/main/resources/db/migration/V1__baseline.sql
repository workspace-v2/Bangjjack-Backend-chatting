-- Flyway baseline: dev DB 스키마 덤프 (2026-10-31, scripts/dump-schema.sh)
-- 기존 DB(main/dev)는 baseline-on-migrate 로 이 파일을 실행하지 않는다. 새 DB 를 만들 때만 실행된다.

CREATE TABLE `chat` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `application_id` bigint DEFAULT NULL,
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `message_type` enum('APPLICATION_ACCEPTED','APPLICATION_CANCELED','APPLICATION_REJECTED','APPLICATION_SENT','GROUP_DISBANDED','SYSTEM','USER') COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `room_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_chat_room_id` (`room_id`,`id`),
  CONSTRAINT `FKq0wpdf7d0te1ub2uiq15i2raj` FOREIGN KEY (`room_id`) REFERENCES `chat_room` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `chat_room` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `category` enum('APPLICATION','GENERAL') COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_by` bigint NOT NULL,
  `direct_room_key` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `last_message_at` datetime(6) DEFAULT NULL,
  `room_type` enum('DIRECT','GROUP') COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('CLOSED','OPEN') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UQ_chat_room_direct_key` (`direct_room_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `chat_room_participant` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `last_read_message_id` bigint DEFAULT NULL,
  `status` enum('ACTIVE','LEFT') COLLATE utf8mb4_unicode_ci NOT NULL,
  `unread_count` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `visible_from_message_id` bigint DEFAULT NULL,
  `room_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UQ_participant_room_user` (`room_id`,`user_id`),
  CONSTRAINT `FK677gcppc5fneuseoige64fsnm` FOREIGN KEY (`room_id`) REFERENCES `chat_room` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `departments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `campus` enum('GLOBAL_CAMPUS','MEDICAL_CAMPUS') COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_departments_campus` (`campus`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `lifestyle_checklist_sleep_habits` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `sleep_habit` enum('FREQUENT_WAKING','NONE','SNORING','TEETH_GRINDING','TOSS_AND_TURN') COLLATE utf8mb4_unicode_ci NOT NULL,
  `checklist_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKkdcpbsfhd24ayfgloftupxwkh` (`checklist_id`),
  CONSTRAINT `FKkdcpbsfhd24ayfgloftupxwkh` FOREIGN KEY (`checklist_id`) REFERENCES `lifestyle_checklists` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `lifestyle_checklists` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `bedtime` enum('AFTER_2','BEFORE_22','BETWEEN_22_24','BETWEEN_24_2','IRREGULAR') COLLATE utf8mb4_unicode_ci NOT NULL,
  `call_habit` enum('INSIDE_OK','OUTSIDE_ONLY','WHISPER') COLLATE utf8mb4_unicode_ci NOT NULL,
  `cleaning_cycle` enum('ALMOST_DAILY','ONCE_OR_TWICE_A_WEEK','RARELY','SOMETIMES') COLLATE utf8mb4_unicode_ci NOT NULL,
  `dorm_stay_time` enum('HALF_AND_HALF','MOSTLY_INSIDE','MOSTLY_OUTSIDE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `indoor_temperature` enum('BOTH_SENSITIVE','INSENSITIVE','SENSITIVE_TO_COLD','SENSITIVE_TO_HEAT') COLLATE utf8mb4_unicode_ci NOT NULL,
  `noise_sensitivity` enum('NORMAL','SLIGHTLY_INSENSITIVE','SLIGHTLY_SENSITIVE','VERY_INSENSITIVE','VERY_SENSITIVE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `smoking` enum('CIGARETTE','ELECTRONIC_CIGARETTE','NON_SMOKER') COLLATE utf8mb4_unicode_ci NOT NULL,
  `wake_up_time` enum('AFTER_10','BEFORE_6','BETWEEN_6_8','BETWEEN_8_10','IRREGULAR') COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKiqnj5vuft9om8psbr7rff1e5d` (`user_id`),
  CONSTRAINT `FK2fetpqwgtgc3h6w26eyrt6vv7` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `post_bookmarks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `active` bit(1) NOT NULL,
  `post_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK3hralg8r0y2lc96pbp3km8mrj` (`user_id`,`post_id`),
  KEY `FK5i136pwk4370jt150nyc74mri` (`post_id`),
  CONSTRAINT `FK5i136pwk4370jt150nyc74mri` FOREIGN KEY (`post_id`) REFERENCES `roommate_posts` (`id`),
  CONSTRAINT `FK9b5c09u5arho7ei76d78bn7ww` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `post_shared_lifestyles` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `earphone_usage` bit(1) NOT NULL,
  `item_sharing` enum('BORROW_WITH_PERMISSION','NO_PREFERENCE','USE_SEPARATELY') COLLATE utf8mb4_unicode_ci NOT NULL,
  `lights_out_time` enum('AFTER_03','BEFORE_23','BETWEEN_00_01','BETWEEN_01_02','BETWEEN_23_24','NO_PREFERENCE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `phone_call` enum('NO_PREFERENCE','ONLY_IN_ROOM','SHORT_CALLS_OKAY') COLLATE utf8mb4_unicode_ci NOT NULL,
  `recycling` enum('MANAGE_SEPARATELY','SHARE_BIN') COLLATE utf8mb4_unicode_ci NOT NULL,
  `room_trash_bin_sharing` bit(1) NOT NULL,
  `post_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKe2ibnq7hlxcvqa04xhms2r3i2` (`post_id`),
  CONSTRAINT `FK4auk6kbwpcis25m1qdqqcoups` FOREIGN KEY (`post_id`) REFERENCES `roommate_posts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `posts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `author_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `roommate_applications` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `status` enum('ACCEPTED','CANCELED','PENDING','REJECTED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `applicant_id` bigint NOT NULL,
  `post_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_application_post_id` (`post_id`),
  KEY `IDX_application_applicant_id` (`applicant_id`),
  KEY `IDX_application_post_applicant_status` (`post_id`,`applicant_id`,`status`),
  CONSTRAINT `FKh7jx72unx5gc1xpcevm9rf237` FOREIGN KEY (`applicant_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKo9at7uhdib2t6a7ywh4m8rp5e` FOREIGN KEY (`post_id`) REFERENCES `roommate_posts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `roommate_group_members` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `role` enum('LEADER','MEMBER') COLLATE utf8mb4_unicode_ci NOT NULL,
  `group_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UQ_group_member_group_user_active` (`group_id`,`user_id`,`deleted_at`),
  KEY `IDX_group_member_user_role` (`user_id`,`role`),
  CONSTRAINT `FKipmw5jnsifv5kj6knwdverukh` FOREIGN KEY (`group_id`) REFERENCES `roommate_groups` (`id`),
  CONSTRAINT `FKiqb9103a3xwwxu6oy47vl7vfm` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `roommate_groups` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `post_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UQ_group_post_id` (`post_id`),
  CONSTRAINT `FKp09vb4yrnoooqr0mxfqdudyaw` FOREIGN KEY (`post_id`) REFERENCES `roommate_posts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `roommate_posts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `description` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `dormitory` enum('DORM_1','DORM_2','DORM_3') COLLATE utf8mb4_unicode_ci NOT NULL,
  `recruit_member_count` int NOT NULL,
  `room_size` enum('FOUR_PERSON','THREE_PERSON','TWO_PERSON') COLLATE utf8mb4_unicode_ci NOT NULL,
  `semester` enum('SIXTEEN_WEEKS','TWENTY_FIVE_WEEKS') COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('CLOSED','OPEN') COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK2row1dlxkg5an4b2me3dmrine` (`user_id`),
  CONSTRAINT `FK2row1dlxkg5an4b2me3dmrine` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `roommate_preferences` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `first_priority` enum('BEDTIME','CALL_HABIT','CLEANING_HABIT','DORM_STAY_TIME','INDOOR_TEMPERATURE','ITEM_SHARING','NOISE_SENSITIVITY','SLEEP_HABIT','SMOKING','WAKE_UP_TIME') COLLATE utf8mb4_unicode_ci NOT NULL,
  `second_priority` enum('BEDTIME','CALL_HABIT','CLEANING_HABIT','DORM_STAY_TIME','INDOOR_TEMPERATURE','ITEM_SHARING','NOISE_SENSITIVITY','SLEEP_HABIT','SMOKING','WAKE_UP_TIME') COLLATE utf8mb4_unicode_ci NOT NULL,
  `third_priority` enum('BEDTIME','CALL_HABIT','CLEANING_HABIT','DORM_STAY_TIME','INDOOR_TEMPERATURE','ITEM_SHARING','NOISE_SENSITIVITY','SLEEP_HABIT','SMOKING','WAKE_UP_TIME') COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK484accsokr0hlnd2ab2un7a01` (`user_id`),
  CONSTRAINT `FKnsr2onpegpihfftiwaicmtmtg` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `birth_year` int DEFAULT NULL,
  `campus` enum('GLOBAL_CAMPUS','MEDICAL_CAMPUS') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `dormitory` enum('DORM_1','DORM_2','DORM_3') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `email` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `gender` enum('FEMALE','MALE') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `grade` int DEFAULT NULL,
  `is_checklist_registered` bit(1) NOT NULL,
  `is_onboarded` bit(1) NOT NULL,
  `is_roommate_preference_registered` bit(1) NOT NULL,
  `profile_image` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `provider_id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `semester` enum('SIXTEEN_WEEKS','TWENTY_FIVE_WEEKS') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `department_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`),
  UNIQUE KEY `UK6jdo1l976be85wv43w6x6e6x2` (`provider_id`),
  KEY `FKsbg59w8q63i0oo53rlgvlcnjq` (`department_id`),
  CONSTRAINT `FKsbg59w8q63i0oo53rlgvlcnjq` FOREIGN KEY (`department_id`) REFERENCES `departments` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


