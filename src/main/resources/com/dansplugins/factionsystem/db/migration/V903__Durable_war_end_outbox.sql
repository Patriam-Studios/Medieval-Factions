create table `mf_war_end_outbox`(
    `id` varchar(36) primary key not null,
    `faction_id` varchar(36) not null,
    `other_faction_id` varchar(36) not null,
    `reason` varchar(32) not null,
    `acting_faction_id` varchar(36),
    `ended_at_epoch_millis` bigint not null,
    `was_fully_established` boolean not null
);

create table `mf_war_generation`(
    `faction_id` varchar(36) not null,
    `other_faction_id` varchar(36) not null,
    primary key(`faction_id`, `other_faction_id`)
);

-- An upgrade can begin with wars already in progress. Seed only pairs that have both directional
-- rows; a one-sided interrupted declaration deliberately remains unmarked.
insert into `mf_war_generation`(`faction_id`, `other_faction_id`)
select distinct
    case when relationship.`faction_id` < relationship.`target_id`
        then relationship.`faction_id` else relationship.`target_id` end,
    case when relationship.`faction_id` < relationship.`target_id`
        then relationship.`target_id` else relationship.`faction_id` end
from `mf_faction_relationship` relationship
where relationship.`type` = 'AT_WAR'
and exists (
    select 1
    from `mf_faction_relationship` mirror
    where mirror.`type` = 'AT_WAR'
    and mirror.`faction_id` = relationship.`target_id`
    and mirror.`target_id` = relationship.`faction_id`
);

create table `mf_war_end_acknowledgement`(
    `event_id` varchar(36) not null,
    `consumer_id` varchar(128) not null,
    primary key(`event_id`, `consumer_id`),
    foreign key(`event_id`) references `mf_war_end_outbox`(`id`) on delete cascade
);
